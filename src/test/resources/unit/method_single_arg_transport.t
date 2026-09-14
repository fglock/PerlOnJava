use strict;
use warnings;
use Test::More;

{
    package MethodSingleArgTransport;

    sub new { bless { calls => 0 }, shift }

    sub mutate_argument {
        my ($self, $value) = @_;
        ++$self->{calls};
        $_[1] += 7;
        return join q{:}, scalar(@_), $self->{calls}, $value;
    }
}

my $object = MethodSingleArgTransport->new;
my $argument = 5;

is($object->mutate_argument($argument), '2:1:5',
    'one-argument method call has a fresh invocant-plus-argument frame');
is($argument, 12, 'one-argument method call preserves argument aliasing');
is($object->mutate_argument($argument), '2:2:12',
    'subsequent one-argument method call receives a distinct fresh frame');
is($argument, 19, 'subsequent call retains aliasing');

done_testing;
