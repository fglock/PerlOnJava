use strict;
use warnings;
use Test::More tests => 2;

my @package_array;
(@package_array = split //, 'abc') = 1 .. 10;
is_deeply(\@package_array, [1, 2, 3],
    'nested array assignment keeps the inner split length');

(@{\@package_array} = split //, 'abc') = 1 .. 10;
is_deeply(\@package_array, [1, 2, 3],
    'nested dereferenced array assignment keeps the inner split length');
