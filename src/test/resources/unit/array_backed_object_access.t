use strict;
use warnings;
use Test::More;

{
    package ArrayBackedAccess;

    sub new { bless [1, 2], shift }

    sub add {
        my ($self, $amount) = @_;
        $self->[0] += $amount;
        $self->[1] += $amount;
        return $self->[0] + $self->[1];
    }
}

my $object = ArrayBackedAccess->new;
is($object->add(3), 9, 'compound updates read and write existing array slots');
is_deeply($object, [4, 5], 'array-backed object keeps updated fields');

my $field = \$object->[0];
$object->[0] = 11;
is($$field, 11, 'a reference to an existing field follows later assignments');

my @fields = (4, 5);
{
    local $fields[0] = 40;
    is($fields[0], 40, 'local array element exposes its localized value');
}
is($fields[0], 4, 'local array element restores its original value');

done_testing();
