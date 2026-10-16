use strict;
use warnings;
use Test::More tests => 5;

{
    package ReadOnlyArgumentUnpack;

    sub add_to_count {
        my ($self, $delta) = @_;
        $self->{count} += $delta;
        return $self->{count} + 0;
    }
}

my $object = bless { count => 1 }, 'ReadOnlyArgumentUnpack';
my $integer_delta = 2;
my $result = $object->add_to_count($integer_delta);
is($result, 3,
    'integer method argument updates the object');
is($integer_delta, 2,
    'read-only lexical unpack does not mutate the caller argument');
$result = 99;
is($object->{count}, 3,
    'fresh arithmetic return value does not alias the hash field');

is($object->add_to_count('4'), 7,
    'non-integer method argument follows the ordinary unpack path');
is($object->{count}, 7,
    'fallback keeps the object value consistent');

