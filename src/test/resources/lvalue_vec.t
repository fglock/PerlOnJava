use strict;
use Test::More;
use feature 'say';
use warnings;

# Basic lvalue vec() usage
my $str18 = "abcd";
vec($str18, 0, 8) = 65;  # ASCII value of 'A'
is($str18, "Abcd", "Basic lvalue vec() usage");

# lvalue vec() with different bit width
my $str19 = "abcd";
vec($str19, 0, 16) = 16706;  # 'AB' as 16-bit integer
is(substr($str19, 0, 2), "AB", "lvalue vec() with different bit width");

# lvalue vec() with non-zero offset
my $str20 = "abcd";
vec($str20, 1, 8) = 66;  # ASCII value of 'B'
is($str20, "aBcd", "lvalue vec() with non-zero offset");

# lvalue vec() with larger bit width
my $str21 = "abcdefgh";
vec($str21, 0, 32) = 0x41424344;  # 'ABCD' as 32-bit integer
is(substr($str21, 0, 4), "ABCD", "lvalue vec() with larger bit width");

# lvalue vec() beyond string length
my $str22 = "ab";
vec($str22, 2, 8) = 67;  # ASCII value of 'C'
is($str22, "abC", "lvalue vec() beyond string length");

# lvalue vec() with bit width of 1
my $str23 = "\x55";  # Binary: 01010101
vec($str23, 3, 1) = 1;
is(ord($str23), 0x5D, "lvalue vec() with bit width of 1");  # Binary: 01011101

# Combining multiple lvalue vec() calls
my $str24 = "\0\0";
vec($str24, 0, 8) = 65;  # ASCII value of 'A'
vec($str24, 1, 8) = 66;  # ASCII value of 'B'
is($str24, "AB", "Combining multiple lvalue vec() calls");

done_testing();
