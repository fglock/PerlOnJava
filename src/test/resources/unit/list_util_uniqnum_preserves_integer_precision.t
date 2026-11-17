use strict;
use warnings;
use Test::More;
use List::Util qw(uniqnum);

my @integer_nv_pairs = (
    9007199254740991, 9.007199254740991e15,
    9007199254740992, 9.007199254740992e15,
    9223372036854774784, 9.223372036854774784e18,
    18446744073709549568, 1.8446744073709549568e19,
);
my @expected = (
    9007199254740991,
    9007199254740992,
    9223372036854774784,
    18446744073709549568,
);

is_deeply([uniqnum @integer_nv_pairs], \@expected,
    'uniqnum keeps exact integers and removes equivalent NV values');

done_testing();
