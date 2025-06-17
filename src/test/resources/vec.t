use feature 'say';
use strict;
use warnings;
use Test::More;

# Declare number of tests
plan tests => 36;  # Total number of test assertions

# Test 1: Basic vec() usage
my $str1 = "abcd";
my $val1 = vec($str1, 0, 8);
is($val1, 97, 'Basic vec() usage');

# Test 2: Different bit widths
my $str2 = "ABCD";
my $val2 = vec($str2, 0, 16);
is($val2, 16706, 'Different bit widths');

# Test 3: Non-zero offset
my $str3 = "abcd";
my $val3 = vec($str3, 1, 8);
is($val3, 98, 'Non-zero offset');

# Test 4: Larger bit width
my $str4 = "abcdefgh";
my $val4 = vec($str4, 0, 32);
is($val4, 1633837924, 'Larger bit width');

# Test 5: Offset beyond string length
my $str5 = "ab";
my $val5 = vec($str5, 2, 8);
is($val5, 0, 'Offset beyond string length');

# Test 7: Empty string
my $str7 = "";
my $val7 = vec($str7, 0, 8);
is($val7, 0, 'Empty string');

# Test 9: Large offset
my $str9 = "a" x 1000;
my $val9 = vec($str9, 999, 8);
is($val9, 97, 'Large offset');

# Test 10: Maximum bit width (32 bits)
my $str10 = "abcdefgh";
my $val10 = vec($str10, 1, 32);
is($val10, 1701209960, 'Maximum bit width (32 bits)');

# Test 11: Bit width of 1
my $str11 = "\x55";  # Binary: 01010101
my $val11 = vec($str11, 3, 1);
is($val11, 0, 'Bit width of 1');

# Test 12: Accessing individual bits
my $str12 = "\xAA";  # Binary: 10101010
for my $i (0..7) {
    my $expected = $i % 2 == 0 ? 0 : 1;
    my $bit_val = vec($str12, $i, 1);
    is($bit_val, $expected, "Accessing individual bit $i");
}

# Test 13: Combining multiple vec() calls
my $str13 = "abcd";
my $combined = vec($str13, 0, 8) | (vec($str13, 1, 8) << 8);
is($combined, 25185, 'Combining multiple vec() calls');

# Test 14: vec() with floating-point offset
my $str14 = "abcd";
my $val14 = vec($str14, 1.7, 8);
is($val14, 98, 'vec() with floating-point offset');

# Test 15: vec() with string offset
my $str15 = "abcd";
my $val15 = vec($str15, "2", 8);
is($val15, 99, 'vec() with string offset');

# Additional Test: Bit width of 1 at different positions
my $str16 = "\xAA";  # Binary: 10101010
for my $i (0..7) {
    my $bit_val = vec($str16, $i, 1);
    is($bit_val, ($i % 2 == 0) ? 0 : 1, "Bit width of 1 at position $i");
}

# Additional Test: Bit width of 1 with different strings
my $str17 = "\xFF";  # Binary: 11111111
for my $i (0..7) {
    my $bit_val = vec($str17, $i, 1);
    is($bit_val, 1, "Bit width of 1 with all bits set at position $i");
}

done_testing();
