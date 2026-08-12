use strict;
use warnings;
use Test::More tests => 3;

is(length('z'x1e3), 1000, 'repeat accepts an adjacent scientific literal');
is(length('z'x1_0), 10, 'repeat accepts underscores in an adjacent integer');
my %value = (x1e3 => 7);
is($value{x1e3}, 7, 'the same token remains a bareword outside infix position');
