use strict;
use warnings;
use Test::More;

our $V = $V = 119;
is($V, 119, 'our initializer can read its own package variable under strict vars');

done_testing;
