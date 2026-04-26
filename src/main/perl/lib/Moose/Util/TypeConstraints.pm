package Moose::Util::TypeConstraints;

# PerlOnJava Moose::Util::TypeConstraints stub.
#
# Provides the most common subroutines exported by the upstream module so
# code that imports them at compile time doesn't fail. Type registration is
# tracked in a flat hash; declared types are accepted but not deeply enforced
# (the Moose shim's `has isa => 'TypeName'` uses Moose.pm's own translator,
# which falls back to a class-name isa check for unknown names).
#
# This is enough for many CPAN modules that just do:
#
#   use Moose::Util::TypeConstraints;
#   subtype 'PositiveInt', as 'Int', where { $_ > 0 };
#   enum 'Direction', [qw(north south east west)];
#
# See dev/modules/moose_support.md.

use strict;
use warnings;

our $VERSION = '2.4000';

use Carp ();
use Scalar::Util ();
use Exporter 'import';

our @EXPORT = qw(
    type subtype as where message optimize_as
    coerce from via
    enum union
    class_type role_type duck_type
    find_type_constraint register_type_constraint
    create_type_constraint_union
);
our @EXPORT_OK = @EXPORT;

# Registry of declared types. Values are hashrefs:
#   { name => $name, parent => $parent, constraint => $coderef, message => $coderef }
my %TYPES;

sub _store {
    my $def = shift;
    $TYPES{ $def->{name} } = $def;
    return $def;
}

# subtype 'Name', as 'Parent', where { ... }, message { ... };
sub subtype {
    my $name = shift;

    # Anonymous subtype: subtype as 'Parent', where { ... }
    if (ref $name eq 'HASH' || @_ == 0) {
        return { %{ $name || {} }, name => undef, anonymous => 1 };
    }

    my %opts;
    while (@_) {
        my $key = shift;
        if (ref $key eq 'HASH') {
            %opts = (%opts, %$key);
        }
        else {
            $opts{$key} = shift;
        }
    }
    return _store({ name => $name, %opts });
}

sub type {
    my $name = shift;
    my %opts = @_ == 1 && ref $_[0] eq 'HASH' ? %{ $_[0] } : @_;
    return _store({ name => $name, %opts });
}

# These are the "DSL" keywords. They return key/value pairs that subtype()
# stitches together.
sub as          { (parent     => $_[0]) }
sub where (&)   { (constraint => $_[0]) }
sub message (&) { (message    => $_[0]) }
sub optimize_as (&) { (optimized => $_[0]) }
sub from        { (coerce_from => $_[0]) }
sub via (&)     { (coerce_via  => $_[0]) }

sub coerce {
    my $name = shift;
    my %opts = @_;
    my $type = $TYPES{$name} or do {
        Carp::carp("Cannot apply coerce to unknown type '$name'");
        return;
    };
    push @{ $type->{coercions} ||= [] }, \%opts;
    return $type;
}

sub enum {
    my $name = shift;
    my $values = ref $_[0] eq 'ARRAY' ? $_[0] : [@_];
    my %ok = map { $_ => 1 } @$values;
    return _store({
        name       => $name,
        parent     => 'Str',
        constraint => sub { defined $_[0] && exists $ok{$_[0]} },
        values     => $values,
    });
}

sub union {
    my ($name, $members) = @_;
    return _store({
        name    => $name,
        parent  => 'Any',
        members => $members,
    });
}

sub class_type {
    my ($name, $opts) = @_;
    my $class = $opts && $opts->{class} ? $opts->{class} : $name;
    return _store({
        name       => $name,
        parent     => 'Object',
        class      => $class,
        constraint => sub {
            Scalar::Util::blessed($_[0]) && $_[0]->isa($class);
        },
    });
}

sub role_type {
    my ($name, $opts) = @_;
    my $role = $opts && $opts->{role} ? $opts->{role} : $name;
    return _store({
        name       => $name,
        parent     => 'Object',
        role       => $role,
        constraint => sub {
            Scalar::Util::blessed($_[0]) && $_[0]->can('does') && $_[0]->does($role);
        },
    });
}

sub duck_type {
    my $name = shift;
    my $methods = ref $_[0] eq 'ARRAY' ? $_[0] : [@_];
    return _store({
        name       => $name,
        parent     => 'Object',
        methods    => $methods,
        constraint => sub {
            my $val = $_[0];
            return 0 unless Scalar::Util::blessed($val);
            for my $m (@$methods) {
                return 0 unless $val->can($m);
            }
            1;
        },
    });
}

sub find_type_constraint           { $TYPES{ $_[0] } }
sub register_type_constraint       { _store({ %{ $_[0] } }) }
sub create_type_constraint_union   { union(@_) }

1;

__END__

=head1 NAME

Moose::Util::TypeConstraints - PerlOnJava compatibility stub

=head1 DESCRIPTION

A best-effort stub of L<Moose::Util::TypeConstraints>. Type declarations are
accepted and remembered but not deeply enforced. Sufficient for code that
declares types at compile time without later relying on the full Moose MOP.

See C<dev/modules/moose_support.md>.

=cut
