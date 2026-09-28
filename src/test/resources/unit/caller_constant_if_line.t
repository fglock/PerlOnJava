use strict;
use warnings;
use Test::More;

if (1) {
    is(sub { (caller 0)[2] }->(), __LINE__,
        'caller retains the source line inside a constant conditional');
}

done_testing;
