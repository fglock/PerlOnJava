use strict;
use warnings;
use Test::More;
use Tie::Array::Packed;

for my $type (qw(c C F f d i I j J s! S! l! L! n N v V)) {
    my $array = "Tie::Array::Packed::$type"->make(1 .. 5);
    is("@$array", '1 2 3 4 5', "$type initial values");
    push @$array, 6;
    is(pop @$array, 6, "$type push and pop");
}

my $array = Tie::Array::Packed::Integer->make(1, 3, 5, 7);
is_deeply([splice(@$array, 1, 2, 2, 4, 6)], [3, 5], 'splice returns removed values');
is_deeply([@$array], [1, 2, 4, 6, 7], 'splice replaces values');

my $object = tied(@$array);
is($object->bsearch(4), 2, 'binary search exact match');
is($object->bsearch_le(5), 2, 'binary search lower bound');
is($object->bsearch_ge(5), 3, 'binary search upper bound');

$object->reverse;
is_deeply([@$array], [7, 6, 4, 2, 1], 'reverse');
$object->rotate(2);
is_deeply([@$array], [4, 2, 1, 7, 6], 'rotate left');
$object->rotate(-1);
is_deeply([@$array], [6, 4, 2, 1, 7], 'rotate right');

my $packed = pack('n*', 10, 20, 30);
my $network = Tie::Array::Packed::UnsignedShortNet->make_with_packed($packed);
is_deeply([@$network], [10, 20, 30], 'packed initializer');
is(tied(@$network)->string, $packed, 'packed storage string');

done_testing;
