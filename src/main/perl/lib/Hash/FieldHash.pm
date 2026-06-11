package Hash::FieldHash;

use strict;
use warnings;

our $VERSION = '0.15';

use Exporter 'import';
use Carp ();
use Hash::Util::FieldHash ();

our @EXPORT_OK   = qw(fieldhash fieldhashes from_hash to_hash);
our %EXPORT_TAGS = (all => \@EXPORT_OK);

my %FIELDS;

sub fieldhash (\%;$$) {
    my ($hash, $name, $package) = @_;
    %$hash = ();
    Hash::Util::FieldHash::fieldhash(%$hash);

    if (defined $name) {
        $package ||= caller;
        $FIELDS{$package}{$name} = $hash;
        no strict 'refs';
        *{"${package}::$name"} = sub {
            my $self = shift;
            Carp::croak("The ${name}() method must be called as an instance method")
                unless ref $self;
            if (@_) {
                $hash->{$self} = shift;
                return $self;
            }
            return $hash->{$self};
        };
    }

    return;
}

sub fieldhashes {
    foreach my $hash_ref (@_) {
        Carp::croak('fieldhashes() requires hash references')
            unless ref($hash_ref) eq 'HASH';
        fieldhash(%$hash_ref);
    }
    return;
}

sub from_hash {
    my ($object, @args) = @_;
    Carp::croak('from_hash() must be called with an object')
        unless ref $object;

    my $fields;
    if (@args == 1 && ref($args[0]) eq 'HASH') {
        $fields = $args[0];
    }
    elsif (@args == 1 && ref($args[0])) {
        Carp::croak('must be a HASH reference');
    }
    else {
        Carp::croak('Odd number of parameters') if @args % 2;
        $fields = { @args };
    }

    for my $name (keys %$fields) {
        my ($package, $field) = _resolve_field(ref($object), $name);
        Carp::croak(qq{No such field "$name"}) unless defined $field;
        $FIELDS{$package}{$field}{$object} = $fields->{$name};
    }

    return $object;
}

sub to_hash {
    my ($object, $fully_qualify) = @_;
    Carp::croak('to_hash() must be called with an object')
        unless ref $object;

    my %out;
    for my $package (_class_linearization(ref($object))) {
        next unless $FIELDS{$package};
        for my $field (keys %{ $FIELDS{$package} }) {
            my $hash = $FIELDS{$package}{$field};
            next unless exists $hash->{$object};
            my $key = $fully_qualify ? "${package}::$field" : $field;
            $out{$key} = $hash->{$object};
        }
    }

    return \%out;
}

sub _resolve_field {
    my ($class, $name) = @_;

    if ($name =~ /\A(.+)::([^:]+)\z/) {
        return exists $FIELDS{$1}{$2} ? ($1, $2) : ();
    }

    for my $package (_class_linearization($class)) {
        return ($package, $name) if exists $FIELDS{$package}{$name};
    }

    return;
}

sub _class_linearization {
    my ($class, $seen) = @_;
    $seen ||= {};
    return () if !$class || $seen->{$class}++;

    no strict 'refs';
    return ($class, map { _class_linearization($_, $seen) } @{"${class}::ISA"});
}

1;
