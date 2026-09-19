use strict;
use warnings;
use Test::More;

is(0x.0p0, 0, 'hexadecimal float may omit an integer part');
is(0x.8p0, 0.5, 'hexadecimal fractional literal has the expected value');

done_testing;
