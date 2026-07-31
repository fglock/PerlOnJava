package JSON::Eval;

use strict;
use warnings;
use Scalar::Util qw(blessed);

our $VERSION = '0.002';

sub new {
    my ($class, $json) = @_;
    $json = do { require JSON::MaybeXS; JSON::MaybeXS->new } unless @_ > 1;
    bless \$json, $class;
}

sub AUTOLOAD {
    my $self = shift;
    our $AUTOLOAD;
    (my $method = $AUTOLOAD) =~ s/.*:://;
    my $result = $$self->$method(@_);
    return $self if ref($result) && $result == $$self;
    $result;
}

sub decode {
    my ($self, @args) = @_;
    my $object = $$self->decode(@args);
    _eval_object($self, $object);
}

sub encode {
    my ($self, @args) = @_;
    $$self->encode(_deparse_object($self, @args));
}

sub _eval_object {
    my ($self, $object) = @_;
    if (ref($object) eq 'HASH' && keys(%$object) == 1 && exists $object->{'$eval'}) {
        my $code = $object->{'$eval'};
        local $@;
        my $result = eval $code;
        die $@ if $@;
        return $result;
    }
    if (ref($object) eq 'HASH' && keys(%$object) == 1 && exists $object->{'$scalar'}) {
        my $value = _eval_object($self, $object->{'$scalar'});
        return \$value;
    }
    if (ref($object) eq 'ARRAY') {
        my @result;
        push @result, ref($_) ? _eval_object($self, $_) : $_ for @$object;
        return \@result;
    }
    if (ref($object) eq 'HASH') {
        my %result;
        # Avoid a nested map expression here: the JVM backend can otherwise
        # reuse temporary registers while evaluating sibling JSON values.
        for my $key (keys %$object) {
            $result{$key} = ref($object->{$key})
                ? _eval_object($self, $object->{$key})
                : $object->{$key};
        }
        return \%result;
    }
    $object;
}

sub _deparse_object {
    my ($self, $object) = @_;
    if (ref($object) eq 'CODE') {
        require PadWalker;
        my $closed = PadWalker::closed_over($object);
        die "Cannot serialize coderef that closes over lexical variables to JSON: " . join(',', sort keys %$closed)
            if keys %$closed;
        require B::Deparse;
        my $deparse = B::Deparse->new;
        $deparse->ambient_pragmas(strict => 'all', warnings => 'all');
        return { '$eval' => 'sub ' . $deparse->coderef2text($object) };
    }
    if (ref($object) eq 'ARRAY') {
        return [ map { ref($_) ? _deparse_object($self, $_) : $_ } @$object ];
    }
    if (ref($object) eq 'SCALAR' || ref($object) eq 'REF') {
        return { '$scalar' => _deparse_object($self, $$object) };
    }
    if (ref($object) eq 'HASH') {
        my %result;
        for my $key (keys %$object) {
            $result{$key} = ref($object->{$key})
                ? _deparse_object($self, $object->{$key})
                : $object->{$key};
        }
        return \%result;
    }
    if (blessed($object) && $self->can('convert_blessed') && $self->convert_blessed && $object->can('TO_JSON')) {
        return _deparse_object($self, $object->TO_JSON);
    }
    $object;
}

sub DESTROY { }

1;
