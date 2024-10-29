#
#   This Perl script is part of the project's examples and demonstrates various Perl features.
#   It is located in the src/test/resources directory.
#
#   Perl test files in src/test/resources are executed during the build process by Maven/Gradle.
#   This ensures that the Perl scripts are tested for correctness as part of the continuous integration pipeline.
#
#   To run the tests manually, you can use the following commands:
#     - For Maven: `mvn test`
#     - For Gradle: `gradle test`
#
#   These commands will compile the Java code, run the Java and Perl tests, and generate test reports.
#
#   Ensure that any new Perl scripts added to src/test/resources follow the project's testing conventions.
#

use strict;
use feature "say";

###################
# Arithmetic Operators

# Addition
my $a = 5 + 3;
print "not " if $a != 8;
say "ok # 5 + 3 equals 8";

# Subtraction
$a = 10 - 2;
print "not " if $a != 8;
say "ok # 10 - 2 equals 8";

# Multiplication
$a = 4 * 2;
print "not " if $a != 8;
say "ok # 4 * 2 equals 8";

# Division
$a = 16 / 2;
print "not " if $a != 8;
say "ok # 16 / 2 equals 8";

# Modulus
$a = 10 % 3;
print "not " if $a != 1;
say "ok # 10 % 3 equals 1";

# Exponentiation
$a = 2**3;
print "not " if $a != 8;
say "ok # 2 ** 3 equals 8";

###################
# Comparison Operators

# Numeric Equality
$a = 5 == 5;
print "not " if !$a;
say "ok # 5 == 5 is true";

# Numeric Inequality
$a = 5 != 4;
print "not " if !$a;
say "ok # 5 != 4 is true";

# Greater Than
$a = 10 > 5;
print "not " if !$a;
say "ok # 10 > 5 is true";

# Less Than
$a = 3 < 8;
print "not " if !$a;
say "ok # 3 < 8 is true";

# Greater Than or Equal To
$a = 7 >= 7;
print "not " if !$a;
say "ok # 7 >= 7 is true";

# Less Than or Equal To
$a = 6 <= 6;
print "not " if !$a;
say "ok # 6 <= 6 is true";

###################
# String Operators

# String Concatenation
my $str = "Hello, " . "world!";
print "not " if $str ne "Hello, world!";
say "ok # String concatenation 'Hello, world!'";

# String Equality
$a = "foo" eq "foo";
print "not " if !$a;
say "ok # 'foo' eq 'foo' is true";

# String Inequality
$a = "foo" ne "bar";
print "not " if !$a;
say "ok # 'foo' ne 'bar' is true";

# String Greater Than
$a = "abc" gt "abb";
print "not " if !$a;
say "ok # 'abc' gt 'abb' is true";

# String Less Than
$a = "abc" lt "abd";
print "not " if !$a;
say "ok # 'abc' lt 'abd' is true";

###################
# Substring Tests

# Substring with positive offset
$str = "Hello, World!";
my $substr = substr( $str, 7 );
print "not " if $substr ne "World!";
say "ok # substr('Hello, World!', 7)";

# Substring with positive offset and length
$substr = substr( $str, 7, 5 );
print "not " if $substr ne "World";
say "ok # substr('Hello, World!', 7, 5)";

# Substring with negative offset
$substr = substr( $str, -6 );
print "not " if $substr ne "World!";
say "ok # substr('Hello, World!', -6)";

# Substring with negative offset and length
$substr = substr( $str, -6, 5 );
print "not " if $substr ne "World";
say "ok # substr('Hello, World!', -6, 5)";

# Substring with offset out of bounds
$substr = substr( $str, 20 );
print "not " if $substr ne "";
say "ok # substr('Hello, World!', 20)";

# Substring with length out of bounds
$substr = substr( $str, 7, 20 );
print "not " if $substr ne "World!";
say "ok # substr('Hello, World!', 7, 20)";

# Substring with negative length
$substr = substr( $str, 7, -1 );
print "not " if $substr ne "World";
say "ok # substr('Hello, World!', 7, -1)";

###################
# sprintf tests

# Test integer formatting
my $int = sprintf( "%d", 42 );
print "not " if $int ne "42";
say "ok # sprintf('%d', 42)";

# Test floating-point formatting
my $float = sprintf( "%.2f", 3.14159 );
print "not " if $float ne "3.14" && $float ne "3,14";
say "ok # sprintf('%.2f', 3.14159)";

# Test string formatting
$str = sprintf( "%s", "Hello, World!" );
print "not " if $str ne "Hello, World!";
say "ok # sprintf('%s', 'Hello, World!')";

# Test multiple arguments
my $formatted = sprintf( "Name: %s, Age: %d", "Alice", 30 );
print "not " if $formatted ne "Name: Alice, Age: 30";
say "ok # sprintf('Name: %s, Age: %d', 'Alice', 30)";

# Test padding and width
my $padded = sprintf( "|%10s|", "test" );
print "not " if $padded ne "|      test|";
say "ok # sprintf('|%10s|', 'test') |$padded|";

# Test negative width
my $negative_width = sprintf( "|%-10s|", "test" );
print "not " if $negative_width ne "|test      |";
say "ok # sprintf('|%-10s|', 'test') |$negative_width|";

## # Test invalid format string
## my $invalid_format;
## eval { $invalid_format = sprintf( "%z", "data" ) };
## my $error = $@;
## print "not " if !$error;    # Expecting an error
## say "ok # sprintf with invalid format string causes an error <$error> <$invalid_format>";

###################
# Logical Operators

# Logical AND
$a = 1 && 1;
print "not " if !$a;
say "ok # 1 && 1 is true";

# Logical OR
$a = 0 || 1;
print "not " if !$a;
say "ok # 0 || 1 is true";

# Logical NOT
$a = !0;
print "not " if !$a;
say "ok # !0 is true";

###################
# Defined-or Operator (//)

# Test with undefined left operand
my $undefined;
my $result = $undefined // "default";
print "not " if $result ne "default";
say "ok # undefined // 'default' equals 'default'";

# Test with defined left operand
my $defined = "value";
$result = $defined // "default";
print "not " if $result ne "value";
say "ok # 'value' // 'default' equals 'value'";

# Test with false but defined left operand
my $false = 0;
$result = $false // "default";
print "not " if $result != 0;
say "ok # 0 // 'default' equals 0";

# Test with empty string as left operand
my $empty = "";
$result = $empty // "default";
print "not " if $result ne "";
say "ok # '' // 'default' equals ''";

###################
# Bitwise Operators

# Bitwise AND
$a = 5 & 3;
print "not " if $a != 1;
say "ok # 5 & 3 equals 1";

# Bitwise OR
$a = 5 | 3;
print "not " if $a != 7;
say "ok # 5 | 3 equals 7";

# Bitwise XOR
$a = 5 ^ 3;
print "not " if $a != 6;
say "ok # 5 ^ 3 equals 6";

# Bitwise NOT
$a = ~5;
print "not " if ($a & 0xFFFF) != 65530;
say "ok # ~5 equals 65530: <" . ($a & 0xFFFF) . ">";

# Left Shift
$a = 5 << 1;
print "not " if $a != 10;
say "ok # 5 << 1 equals 10";

# Right Shift
$a = 5 >> 1;
print "not " if $a != 2;
say "ok # 5 >> 1 equals 2";

###################
# Compound Assignment Operators

# Addition Assignment
$a = 5;
$a += 3;
print "not " if $a != 8;
say "ok # 5 += 3 equals 8";

# Subtraction Assignment
$a = 10;
$a -= 2;
print "not " if $a != 8;
say "ok # 10 -= 2 equals 8";

# Multiplication Assignment
$a = 4;
$a *= 2;
print "not " if $a != 8;
say "ok # 4 *= 2 equals 8";

# Division Assignment
$a = 16;
$a /= 2;
print "not " if $a != 8;
say "ok # 16 /= 2 equals 8";

# Modulus Assignment
$a = 10;
$a %= 3;
print "not " if $a != 1;
say "ok # 10 %= 3 equals 1";

# Exponentiation Assignment
$a = 2;
$a**= 3;
print "not " if $a != 8;
say "ok # 2 **= 3 equals 8";

# Bitwise AND Assignment
$a = 12;     # 1100 in binary
$a &= 10;    # 1010 in binary
print "not " if $a != 8;
say "ok # 12 &= 10 equals 8";

# Bitwise OR Assignment
$a = 5;      # 0101 in binary
$a |= 3;     # 0011 in binary
print "not " if $a != 7;
say "ok # 5 |= 3 equals 7";

# Bitwise XOR Assignment
$a = 5;      # 0101 in binary
$a ^= 3;     # 0011 in binary
print "not " if $a != 6;
say "ok # 5 ^= 3 equals 6";

# Bitwise Shift Left Assignment
$a = 2;      # 0010 in binary
$a <<= 2;    # Shift left by 2 bits
print "not " if $a != 8;
say "ok # 2 <<= 2 equals 8";

# Bitwise Shift Right Assignment
$a = 8;      # 1000 in binary
$a >>= 2;    # Shift right by 2 bits
print "not " if $a != 2;
say "ok # 8 >>= 2 equals 2";

# Test oct
my $oct_value = oct('0777');
print "not " if $oct_value != 511;
say "ok # oct('0777') equals 511";

$oct_value = oct('0xFF');
print "not " if $oct_value != 255;
say "ok # oct('0xFF') equals 255";

print "not " if oct("0755") != 493;
say "ok # Octal: expected 493";
print "not " if oct("0x1F") != 31;
say "ok # Hex: expected 31";
print "not " if oct("0b1101") != 13;
say "ok # Binary: expected 13";
print "not " if oct("0o755") != 493;
say "ok # Octal with 0o prefix: expected 493";
print "not " if oct(" 0755 ") != 493;
say "ok # Whitespace and octal: expected 493";
print "not " if oct("o755") != 493;
say "ok # Octal with o prefix: expected 493";
print "not " if oct("0xABC_DEF") != 11259375;
say "ok # Hex with underscores: expected 11259375";
print "not " if oct("109") != 8;
say "ok # Octal fallback: expected 8 (10 octal is 8 decimal)";
print "not " if oct("128") != 10;
say "ok # Octal fallback: expected 10 (12 octal is 10 decimal)";

# Test hex
my $hex_value = hex('0xFF');
print "not " if $hex_value != 255; say "ok # hex('0xFF') equals 255";

$hex_value = hex('FF');
print "not " if $hex_value != 255; say "ok # hex('FF') equals 255";


###################
# Repeat Operator (x) - Strings

# Repeat string multiple times
$str = "abc" x 3;
print "not " if $str ne "abcabcabc";
say "ok # 'abc' x 3 equals 'abcabcabc'";

# Repeat string zero times (should return empty string)
$str = "abc" x 0;
print "not " if $str ne "";
say "ok # 'abc' x 0 equals ''";

# Repeat string with empty string
$str = "" x 3;
print "not " if $str ne "";
say "ok # '' x 3 equals ''";

# Repeat string with negative number (should return empty string)
$str = "abc" x -1;
print "not " if $str ne "";
say "ok # 'abc' x -1 equals ''";

# Repeat a single character multiple times
$str = "a" x 5;
print "not " if $str ne "aaaaa";
say "ok # 'a' x 5 equals 'aaaaa'";

# Repeat string with numeric context (treats as a string)
$str = 123 x 2;
print "not " if $str ne "123123";
say "ok # 123 x 2 equals '123123'";

# Mixed content string
$str = "abc123" x 2;
print "not " if $str ne "abc123abc123";
say "ok # 'abc123' x 2 equals 'abc123abc123'";

# Repeat string with a large number
$str = "ab" x 1000;
print "not " if length($str) != 2000;
say "ok # 'ab' x 1000 produces a string of length 2000";

###################
# Repeat Operator (x) - Lists

# Repeat list multiple times
my @list = (1, 2, 3) x 2;
print "not " if "@list" ne "1 2 3 1 2 3";
say "ok # (1, 2, 3) x 2 equals '1 2 3 1 2 3'";

# Repeat list zero times (should return an empty list)
@list = (1, 2, 3) x 0;
print "not " if scalar(@list) != 0;
say "ok # (1, 2, 3) x 0 equals an empty list";

# Repeat list with an empty list (should return an empty list)
@list = () x 3;
print "not " if scalar(@list) != 0;
say "ok # () x 3 equals an empty list";

# Repeat list with negative number (should return an empty list)
@list = (1, 2, 3) x -1;
print "not " if scalar(@list) != 0;
say "ok # (1, 2, 3) x -1 equals an empty list";

# Repeat list with mixed content
@list = ('a', 'b', 123) x 3;
print "not " if "@list" ne "a b 123 a b 123 a b 123";
say "ok # ('a', 'b', 123) x 3 equals 'a b 123 a b 123 a b 123'";

# Repeat list with a large number of repetitions
@list = (1, 2) x 1000;
print "not " if scalar(@list) != 2000;
say "ok # (1, 2) x 1000 produces a list with 2000 elements";

# Unary minus

# Test unary minus with plain strings and numbers

# Test with plain strings
$result = -("+");
print "not " if $result ne "-"; say "ok # unary minus on + results in -";

$result = -("-");
print "not " if $result ne "+"; say "ok # unary minus on - results in +";

$result = -("-aa");
print "not " if $result ne "+aa"; say "ok # unary minus on -aa results in +aa <$result>";

$result = -("a");
print "not " if $result ne "-a"; say "ok # unary minus on a results in -a";

$result = -("aa");
print "not " if $result ne "-aa"; say "ok # unary minus on aa results in -aa";

# Test with strings starting with numbers
$result = -("0aa");
print "not " if $result ne "0"; say "ok # unary minus on 0aa results in 0 <$result>";

$result = -("12aa");
print "not " if $result ne "-12"; say "ok # unary minus on 12aa results in -12";

# Test with non-numeric strings
$result = -("abc");
print "not " if $result ne "-abc"; say "ok # unary minus on abc results in -abc";

$result = -("-abc");
print "not " if $result ne "+abc"; say "ok # unary minus on -abc results in +abc";

# Test with edge cases and invalid formats
$result = -("");
print "not " if $result ne "0"; say "ok # unary minus on  results in 0";

$result = -("123");
print "not " if $result ne "-123"; say "ok # unary minus on 123 results in -123";

###################
# Unary Plus Operator

# Unary plus on a positive number
my $b = +5;
print "not " if $b != 5;
say "ok # +5 equals 5";

# Unary plus on a negative number
$b = +-5;
print "not " if $b != -5;
say "ok # +-5 equals -5";

###################
# Increment and Decrement Operators

# Pre-increment
$b = 5;
++$b;
print "not " if $b != 6;
say "ok # ++5 equals 6";

# Post-increment
$b = 5;
$b++;
print "not " if $b != 6;
say "ok # 5++ equals 6";

# Pre-decrement
$b = 5;
--$b;
print "not " if $b != 4;
say "ok # --5 equals 4";

# Post-decrement
$b = 5;
$b--;
print "not " if $b != 4;
say "ok # 5-- equals 4";

###################
# Floating-point Arithmetic

# Floating-point addition
my $c = 5.5 + 2.5;
print "not " if $c != 8.0;
say "ok # 5.5 + 2.5 equals 8.0";

# Floating-point subtraction
$c = 5.5 - 2.5;
print "not " if $c != 3.0;
say "ok # 5.5 - 2.5 equals 3.0";

# Floating-point multiplication
$c = 2.5 * 2.0;
print "not " if $c != 5.0;
say "ok # 2.5 * 2.0 equals 5.0";

# Floating-point division
$c = 5.0 / 2.0;
print "not " if $c != 2.5;
say "ok # 5.0 / 2.0 equals 2.5";

###################
# Integer Division

# Integer division
$c = int(5 / 2);
print "not " if $c != 2;
say "ok # int(5 / 2) equals 2";

###################
# Trigonometric Functions

# Sine of 0
$c = sin(0);
print "not " if $c != 0;
say "ok # sin(0) equals 0";

# Cosine of 0
$c = cos(0);
print "not " if $c != 1;
say "ok # cos(0) equals 1";

###################
# Square Root and Logarithms

# Square root
$c = sqrt(9);
print "not " if $c != 3;
say "ok # sqrt(9) equals 3";

# Natural logarithm
$c = log(exp(1));
print "not " if $c != 1;
say "ok # log(exp(1)) equals 1";

###################
# Absolute Value

# Absolute value of a negative number
$c = abs(-5);
print "not " if $c != 5;
say "ok # abs(-5) equals 5";

###################
# Random Number Generation

# Random number generation
$c = rand();
print "not " if $c < 0 || $c >= 1;
say "ok # rand() generates a number between 0 and 1";
###################
# Arctangent Function (atan2)

# Arctangent of (1, 1) (should be π/4 or approximately 0.785398)
my $c = atan2(1, 1);
print "not " if abs($c - 0.785398) > 0.000001;
say "ok # atan2(1, 1) is approximately 0.785398 (π/4)";

# Arctangent of (0, 1) (should be 0)
$c = atan2(0, 1);
print "not " if abs($c - 0) > 0.000001;
say "ok # atan2(0, 1) is 0";

# Arctangent of (1, 0) (should be π/2 or approximately 1.570796)
$c = atan2(1, 0);
print "not " if abs($c - 1.570796) > 0.000001;
say "ok # atan2(1, 0) is approximately 1.570796 (π/2)";

# Arctangent of (-1, -1) (should be -3π/4 or approximately -2.356194)
$c = atan2(-1, -1);
print "not " if abs($c + 2.356194) > 0.000001;
say "ok # atan2(-1, -1) is approximately -2.356194 (-3π/4)";
