use feature 'say';
use strict;
use warnings;
# use utf8;

###################
# Perl vec() lvalue Tests

# Test 18: Basic lvalue vec() usage
my $str18 = "abcd";
vec($str18, 0, 8) = 65;  # ASCII value of 'A'
print "not " if $str18 ne "Abcd";
say "ok # Basic lvalue vec() usage <$str18>";

# Test 19: lvalue vec() with different bit width
my $str19 = "abcd";
vec($str19, 0, 16) = 16706;  # 'AB' as 16-bit integer
print "not " if substr($str19, 0, 2) ne "AB";
say "ok # lvalue vec() with different bit width <$str19>";

# Test 20: lvalue vec() with non-zero offset
my $str20 = "abcd";
vec($str20, 1, 8) = 66;  # ASCII value of 'B'
print "not " if $str20 ne "aBcd";
say "ok # lvalue vec() with non-zero offset <$str20>";

# Test 21: lvalue vec() with larger bit width
my $str21 = "abcdefgh";
vec($str21, 0, 32) = 0x41424344;  # 'ABCD' as 32-bit integer
print "not " if substr($str21, 0, 4) ne "ABCD";
say "ok # lvalue vec() with larger bit width <$str21>";

# Test 22: lvalue vec() beyond string length (should extend the string)
my $str22 = "ab";
vec($str22, 2, 8) = 67;  # ASCII value of 'C'
print "not " if $str22 ne "abC";
say "ok # lvalue vec() beyond string length <$str22>";

# Test 23: lvalue vec() with bit width of 1
my $str23 = "\x55";  # Binary: 01010101
vec($str23, 3, 1) = 1;
print "not " if ord($str23) != 0x5D;  # Binary: 01011101
say "ok # lvalue vec() with bit width of 1 <" . sprintf("%08b", ord($str23)) . ">";

# Test 24: Combining multiple lvalue vec() calls
my $str24 = "\0\0";
vec($str24, 0, 8) = 65;  # ASCII value of 'A'
vec($str24, 1, 8) = 66;  # ASCII value of 'B'
print "not " if $str24 ne "AB";
say "ok # Combining multiple lvalue vec() calls <$str24>";

