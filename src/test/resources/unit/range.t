use strict;
use Test::More;
use feature 'say';

# Test 1: Simple numeric range
my @array = (1 .. 5);
my $length = scalar @array;
ok(!($length != 5), 'Numeric range 1..5');

# Test 2: Simple alphabetic range
@array = ('a' .. 'e');
$length = scalar @array;
ok(!($length != 5), 'Alphabetic range \'a\'..\'e\'');

# Test 3: Complex range - string with non-magical characters
@array = ('*x' .. 'az');
$length = scalar @array;
ok(!($length != 1 or $array[0] ne '*x'), 'Non-magical string range \'*x\'..\'az\'');

# Test 4: Range with leading zero but part of magical increment sequence
@array = ("a0" .. "a2");
$length = scalar @array;
ok(!($length != 3 or $array[0] ne 'a0' or $array[2] ne 'a2'), 'Magical string range \'a0\'..\'a2\' <@array>');

# Test 5: Leading zero longer than one character and non-magical
@array = ("00" .. "02");
$length = scalar @array;
ok(!($length != 3 or $array[0] ne '00' or $array[2] ne '02'), 'Leading zero with magical sequence \'00\'..\'02\'');

# Test 6: Leading zero longer than one character but not part of magical increment sequence
@array = ("0z" .. "02");
$length = scalar @array;
ok(!($length != 1 or $array[0] ne '0z'), 'Non-magical string range \'0z\'..\'02\' <@array>');

# Test 7: Incrementing alphabetic range with 'a' as prefix
@array = ('aa' .. 'ad');
$length = scalar @array;
ok(!($length != 4 or $array[3] ne 'ad'), 'Alphabetic range \'aa\'..\'ad\'');

# Test 8: Uppercase alphabetic range
@array = ('A' .. 'C');
$length = scalar @array;
ok(!($length != 3 or $array[0] ne 'A' or $array[2] ne 'C'), 'Uppercase alphabetic range \'A\'..\'C\'');

# Test 9: Numeric range with leading zeroes
@array = ('01' .. '05');
$length = scalar @array;
ok(!($length != 5 or $array[0] ne '01' or $array[4] ne '05'), 'Numeric range \'01\'..\'05\'');

# Test 10: Range starting with mixed characters followed by numbers
@array = ('a9' .. 'b2');
$length = scalar @array;
ok(!($length != 4 or $array[3] ne 'b2'), 'Mixed character/numeric range \'a9\'..\'b2\'');

# Test 11: Empty range
@array = ('z' .. 'a');
$length = scalar @array;
ok(!($length != 1 || $array[0] ne 'z'), 'Empty range \'z\'..\'a\' <@array>');

# Test 12: Full alphabetic range
@array = ('a' .. 'z');
$length = scalar @array;
ok(!($length != 26 or $array[25] ne 'z'), 'Full alphabetic range \'a\'..\'z\'');

# Test 13: Large numeric range
@array = (100 .. 105);
$length = scalar @array;
ok(!($length != 6 or $array[5] ne '105'), 'Large numeric range 100..105');

# Test 14: Reversed range
@array = reverse (1 .. 5);
$length = scalar @array;
ok(!($length != 5 or $array[0] != 5), 'Reversed numeric range \'5..1\'');

# Floating point
@array = (3.14 .. 4.7);
$length = scalar @array;
ok(!($length != 2 or $array[1] ne '4'), 'Floating point <@array>');

# Empty string
@array = ("" .. "a");
$length = scalar @array;
ok(!($length != 1 or $array[0] ne ''), 'Empty string <@array>');

done_testing();
