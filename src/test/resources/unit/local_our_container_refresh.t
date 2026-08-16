use strict;
use warnings;
use Test::More tests => 6;

our ($scalar, @array, %hash);
$scalar = 'outer scalar';
@array = ('outer array');
%hash = (outer => 'hash');

{
    local $scalar;
    local @array;
    local %hash;
    $scalar = 'inner scalar';
    @array = ('inner array');
    %hash = (inner => 'hash');

    is($scalar, 'inner scalar', 'localized our scalar accepts assignment');
    is("@array", 'inner array', 'localized our array accepts assignment');
    is($hash{inner}, 'hash', 'localized our hash accepts assignment');
}

is($scalar, 'outer scalar', 'our scalar is restored after localization');
is("@array", 'outer array', 'our array is restored after localization');
is($hash{outer}, 'hash', 'our hash is restored after localization');
