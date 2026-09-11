use strict;
use warnings;
use Test::More;

is(-24, -24, 'small integer literal retains its value');
is(substr('abcdefghijklmnopqrstuvwxyz', -24), 'cdefghijklmnopqrstuvwxyz',
    'negative literal works as a substring offset');
is(-2_147_483_647, -2147483647, 'underscored small integer literal retains its value');

done_testing;
