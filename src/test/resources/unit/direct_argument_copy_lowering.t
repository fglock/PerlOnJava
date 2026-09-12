use strict;
use warnings;
use Test::More;

{
    package DirectArgumentCopyLoweringObject;

    sub new { bless { x => 1, y => 2 }, shift }

    sub add {
        my ($self, $n) = @_;
        $self->{x} += $n;
        $self->{y} += $n;
        return $self->{x} + $self->{y};
    }
}

sub scalar_copy_is_not_argument_alias {
    my ($value) = @_;
    return $value + 1;
}

my $object = DirectArgumentCopyLoweringObject->new;
is($object->add(1), 5,
    'immediate argument copies support a read-only method body');
is($object->add(1), 7,
    'successive calls retain their ordinary method and argument semantics');
is_deeply($object, { x => 3, y => 4 },
    'mutations through the copied reference still update its referent');

my $value = 41;
is(scalar_copy_is_not_argument_alias($value), 42,
    'read-only scalar argument copy has the expected value');
is($value, 41,
    'read-only scalar argument use does not mutate the caller');

done_testing;
