use feature 'say';
use strict;
use warnings;
# use utf8;

###################
# Perl vec() Function Tests

# Test 1: Basic vec() usage
my $str1 = "abcd";
my $val1 = vec($str1, 0, 8);
print "not " if $val1 != 97;  # ASCII value of 'a'
say "ok # Basic vec() usage";

# Test 2: Different bit widths
my $str2 = "ABCD";
my $val2 = vec($str2, 0, 16);
print "not " if $val2 != 16706;  # 'AB' as 16-bit integer
say "ok # Different bit widths";

# Test 3: Non-zero offset
my $str3 = "abcd";
my $val3 = vec($str3, 1, 8);
print "not " if $val3 != 98;  # ASCII value of 'b'
say "ok # Non-zero offset";

# Test 4: Larger bit width
my $str4 = "abcdefgh";
my $val4 = vec($str4, 0, 32);
print "not " if $val4 != 1633837924;  # 'abcd' as 32-bit integer
say "ok # Larger bit width <$val4>";

# Test 5: Offset beyond string length
my $str5 = "ab";
my $val5 = vec($str5, 2, 8);
print "not " if $val5 != 0;
say "ok # Offset beyond string length";

# Test 7: Empty string
my $str7 = "";
my $val7 = vec($str7, 0, 8);
print "not " if $val7 != 0;
say "ok # Empty string";

# Test 9: Large offset
my $str9 = "a" x 1000;
my $val9 = vec($str9, 999, 8);
print "not " if $val9 != 97;  # ASCII value of 'a'
say "ok # Large offset";

# Test 10: Maximum bit width (32 bits)
my $str10 = "abcdefgh";
my $val10 = vec($str10, 1, 32);
print "not " if $val10 != 1701209960;  # 'bcde' as 32-bit integer
say "ok # Maximum bit width (32 bits)";

# Test 11: Bit width of 1
my $str11 = "\x55";  # Binary: 01010101
my $val11 = vec($str11, 3, 1);
print "not " if $val11 != 0;
say "ok # Bit width of 1 <$val11>";

# Test 12: Accessing individual bits
my $str12 = "\xAA";  # Binary: 10101010
for my $i (0..7) {
    my $expected = $i % 2 == 0 ? 0 : 1;
    my $bit_val = vec($str12, $i, 1);
    print "not " if $bit_val != $expected;
    say "ok # Accessing individual bits <$bit_val $expected>";
    # say "Debug: bit $i, expected $expected, got $bit_val"; # Debug statement
}

# Test 13: Combining multiple vec() calls
my $str13 = "abcd";
my $combined = vec($str13, 0, 8) | (vec($str13, 1, 8) << 8);
print "not " if $combined != 25185;  # 'ab' as 16-bit integer
say "ok # Combining multiple vec() calls";

# Test 14: vec() with floating-point offset (should be truncated)
my $str14 = "abcd";
my $val14 = vec($str14, 1.7, 8);
print "not " if $val14 != 98;  # ASCII value of 'b'
say "ok # vec() with floating-point offset";

# Test 15: vec() with string offset (should be converted to integer)
my $str15 = "abcd";
my $val15 = vec($str15, "2", 8);
print "not " if $val15 != 99;  # ASCII value of 'c'

# Additional Test: Bit width of 1 at different positions
my $str16 = "\xAA";  # Binary: 10101010
for my $i (0..7) {
    my $bit_val = vec($str16, $i, 1);
    # say "Debug: bit $i, value $bit_val"; # Debug statement
    print "not " if $bit_val != (($i % 2 == 0) ? 0 : 1);
    say "ok # Bit width of 1 at different positions <$bit_val>";
}

# Additional Test: Bit width of 1 with different strings
my $str17 = "\xFF";  # Binary: 11111111
for my $i (0..7) {
    my $bit_val = vec($str17, $i, 1);
    # say "Debug: bit $i, value $bit_val"; # Debug statement
    print "not " if $bit_val != 1;
    say "ok # Bit width of 1 with different strings <$bit_val>";
}

