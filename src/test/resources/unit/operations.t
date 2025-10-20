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
use Test::More;
use feature "say";

###################
# Arithmetic Operators

# Addition
my $a = 5 + 3;
ok(!($a != 8), '5 + 3 equals 8');

# Subtraction
$a = 10 - 2;
ok(!($a != 8), '10 - 2 equals 8');

# Multiplication
$a = 4 * 2;
ok(!($a != 8), '4 * 2 equals 8');

# Division
$a = 16 / 2;
ok(!($a != 8), '16 / 2 equals 8');

# Modulus
$a = 10 % 3;
ok(!($a != 1), '10 % 3 equals 1');

# Exponentiation
$a = 2**3;
ok(!($a != 8), '2 ** 3 equals 8');

###################
# Comparison Operators

# Numeric Equality
$a = 5 == 5;
ok($a, '5 == 5 is true');

# Numeric Inequality
$a = 5 != 4;
ok($a, '5 != 4 is true');

# Greater Than
$a = 10 > 5;
ok($a, '10 > 5 is true');

# Less Than
$a = 3 < 8;
ok($a, '3 < 8 is true');

# Greater Than or Equal To
$a = 7 >= 7;
ok($a, '7 >= 7 is true');

# Less Than or Equal To
$a = 6 <= 6;
ok($a, '6 <= 6 is true');

###################
# String Operators

# String Concatenation
my $str = "Hello, " . "world!";
ok(!($str ne "Hello, world!"), 'String concatenation \'Hello, world!\'');

# String Equality
$a = "foo" eq "foo";
ok($a, '\'foo\' eq \'foo\' is true');

# String Inequality
$a = "foo" ne "bar";
ok($a, '\'foo\' ne \'bar\' is true');

# String Greater Than
$a = "abc" gt "abb";
ok($a, '\'abc\' gt \'abb\' is true');

# String Less Than
$a = "abc" lt "abd";
ok($a, '\'abc\' lt \'abd\' is true');

###################
# Substring Tests

# Substring with positive offset
$str = "Hello, World!";
my $substr = substr( $str, 7 );
ok(!($substr ne "World!"), 'substr(\'Hello, World!\', 7)');

# Substring with positive offset and length
$substr = substr( $str, 7, 5 );
ok(!($substr ne "World"), 'substr(\'Hello, World!\', 7, 5)');

# Substring with negative offset
$substr = substr( $str, -6 );
ok(!($substr ne "World!"), 'substr(\'Hello, World!\', -6)');

# Substring with negative offset and length
$substr = substr( $str, -6, 5 );
ok(!($substr ne "World"), 'substr(\'Hello, World!\', -6, 5)');

# Substring with offset out of bounds
$substr = substr( $str, 20 );
ok(!($substr ne ""), 'substr(\'Hello, World!\', 20)');

# Substring with length out of bounds
$substr = substr( $str, 7, 20 );
ok(!($substr ne "World!"), 'substr(\'Hello, World!\', 7, 20)');

# Substring with negative length
$substr = substr( $str, 7, -1 );
ok(!($substr ne "World"), 'substr(\'Hello, World!\', 7, -1)');

###################
# sprintf tests

# Test integer formatting
my $int = sprintf( "%d", 42 );
ok(!($int ne "42"), 'sprintf(\'%d\', 42)');

# Test floating-point formatting
my $float = sprintf( "%.2f", 3.14159 );
ok(!($float ne "3.14" && $float ne "3,14"), 'sprintf(\'%.2f\', 3.14159)');

# Test string formatting
$str = sprintf( "%s", "Hello, World!" );
ok(!($str ne "Hello, World!"), 'sprintf(\'%s\', \'Hello, World!\')');

# Test multiple arguments
my $formatted = sprintf( "Name: %s, Age: %d", "Alice", 30 );
ok(!($formatted ne "Name: Alice, Age: 30"), 'sprintf(\'Name: %s, Age: %d\', \'Alice\', 30)');

# Test padding and width
my $padded = sprintf( "|%10s|", "test" );
ok(!($padded ne "|      test|"), 'sprintf(\'|%10s|\', \'test\') |$padded|');

# Test negative width
my $negative_width = sprintf( "|%-10s|", "test" );
ok(!($negative_width ne "|test      |"), 'sprintf(\'|%-10s|\', \'test\') |$negative_width|');

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
ok($a, '1 && 1 is true');

# Logical OR
$a = 0 || 1;
ok($a, '0 || 1 is true');

# Logical NOT
$a = !0;
ok($a, '!0 is true');

###################
# Defined-or Operator (//)

# Test with undefined left operand
my $undefined;
my $result = $undefined // "default";
ok(!($result ne "default"), 'undefined // \'default\' equals \'default\'');

# Test with defined left operand
my $defined = "value";
$result = $defined // "default";
ok(!($result ne "value"), '\'value\' // \'default\' equals \'value\'');

# Test with false but defined left operand
my $false = 0;
$result = $false // "default";
ok(!($result != 0), '0 // \'default\' equals 0');

# Test with empty string as left operand
my $empty = "";
$result = $empty // "default";
ok(!($result ne ""), '\'\' // \'default\' equals \'\'');

###################
# Bitwise Operators

# Bitwise AND
$a = 5 & 3;
ok(!($a != 1), '5 & 3 equals 1');

# Bitwise OR
$a = 5 | 3;
ok(!($a != 7), '5 | 3 equals 7');

# Bitwise XOR
$a = 5 ^ 3;
ok(!($a != 6), '5 ^ 3 equals 6');

# Bitwise NOT
$a = ~5;
ok(($a & 0xFFFF) == 65530, "~5 equals 65530: <" . ($a & 0xFFFF) . ">");

# Left Shift
$a = 5 << 1;
ok(!($a != 10), '5 << 1 equals 10');

# Right Shift
$a = 5 >> 1;
ok(!($a != 2), '5 >> 1 equals 2');

###################
# Compound Assignment Operators

# Addition Assignment
$a = 5;
$a += 3;
ok(!($a != 8), '5 += 3 equals 8');

# Subtraction Assignment
$a = 10;
$a -= 2;
ok(!($a != 8), '10 -= 2 equals 8');

# Multiplication Assignment
$a = 4;
$a *= 2;
ok(!($a != 8), '4 *= 2 equals 8');

# Division Assignment
$a = 16;
$a /= 2;
ok(!($a != 8), '16 /= 2 equals 8');

# Modulus Assignment
$a = 10;
$a %= 3;
ok(!($a != 1), '10 %= 3 equals 1');

# Exponentiation Assignment
$a = 2;
$a**= 3;
ok(!($a != 8), '2 **= 3 equals 8');

# Bitwise AND Assignment
$a = 12;     # 1100 in binary
$a &= 10;    # 1010 in binary
ok(!($a != 8), '12 &= 10 equals 8');

# Bitwise OR Assignment
$a = 5;      # 0101 in binary
$a |= 3;     # 0011 in binary
ok(!($a != 7), '5 |= 3 equals 7');

# Bitwise XOR Assignment
$a = 5;      # 0101 in binary
$a ^= 3;     # 0011 in binary
ok(!($a != 6), '5 ^= 3 equals 6');

# Bitwise Shift Left Assignment
$a = 2;      # 0010 in binary
$a <<= 2;    # Shift left by 2 bits
ok(!($a != 8), '2 <<= 2 equals 8');

# Bitwise Shift Right Assignment
$a = 8;      # 1000 in binary
$a >>= 2;    # Shift right by 2 bits
ok(!($a != 2), '8 >>= 2 equals 2');

# Test oct
my $oct_value = oct('0777');
ok(!($oct_value != 511), 'oct(\'0777\') equals 511');

$oct_value = oct('0xFF');
ok(!($oct_value != 255), 'oct(\'0xFF\') equals 255');

ok(!(oct("0755") != 493), 'Octal: expected 493');
ok(!(oct("0x1F") != 31), 'Hex: expected 31');
ok(!(oct("0b1101") != 13), 'Binary: expected 13');
ok(!(oct("0o755") != 493), 'Octal with 0o prefix: expected 493');
ok(!(oct(" 0755 ") != 493), 'Whitespace and octal: expected 493');
ok(!(oct("o755") != 493), 'Octal with o prefix: expected 493');
ok(!(oct("0xABC_DEF") != 11259375), 'Hex with underscores: expected 11259375');
ok(!(oct("109") != 8), 'Octal fallback: expected 8 (10 octal is 8 decimal)');
ok(!(oct("128") != 10), 'Octal fallback: expected 10 (12 octal is 10 decimal)');

# Test hex
my $hex_value = hex('0xFF');
ok(!($hex_value != 255), 'hex(\'0xFF\') equals 255');

$hex_value = hex('FF');
ok(!($hex_value != 255), 'hex(\'FF\') equals 255');

###################
# Repeat Operator (x) - Strings

# Repeat string multiple times
$str = "abc" x 3;
ok(!($str ne "abcabcabc"), '\'abc\' x 3 equals \'abcabcabc\'');

# Repeat string zero times (should return empty string)
$str = "abc" x 0;
ok(!($str ne ""), '\'abc\' x 0 equals \'\'');

# Repeat string with empty string
$str = "" x 3;
ok(!($str ne ""), '\'\' x 3 equals \'\'');

# Repeat string with negative number (should return empty string)
$str = "abc" x -1;
ok(!($str ne ""), '\'abc\' x -1 equals \'\'');

# Repeat a single character multiple times
$str = "a" x 5;
ok(!($str ne "aaaaa"), '\'a\' x 5 equals \'aaaaa\'');

# Repeat string with numeric context (treats as a string)
$str = 123 x 2;
ok(!($str ne "123123"), '123 x 2 equals \'123123\'');

# Mixed content string
$str = "abc123" x 2;
ok(!($str ne "abc123abc123"), '\'abc123\' x 2 equals \'abc123abc123\'');

# Repeat string with a large number
$str = "ab" x 1000;
ok(!(length($str) != 2000), '\'ab\' x 1000 produces a string of length 2000');

###################
# Repeat Operator (x) - Lists

# Repeat list multiple times
my @list = (1, 2, 3) x 2;
ok(!("@list" ne "1 2 3 1 2 3"), '(1, 2, 3) x 2 equals \'1 2 3 1 2 3\'');

# Repeat list zero times (should return an empty list)
@list = (1, 2, 3) x 0;
ok(!(scalar(@list) != 0), '(1, 2, 3) x 0 equals an empty list');

# Repeat list with an empty list (should return an empty list)
@list = () x 3;
ok(!(scalar(@list) != 0), '() x 3 equals an empty list');

# Repeat list with negative number (should return an empty list)
@list = (1, 2, 3) x -1;
ok(!(scalar(@list) != 0), '(1, 2, 3) x -1 equals an empty list');

# Repeat list with mixed content
@list = ('a', 'b', 123) x 3;
ok(!("@list" ne "a b 123 a b 123 a b 123"), '(\'a\', \'b\', 123) x 3 equals \'a b 123 a b 123 a b 123\'');

# Repeat list with a large number of repetitions
@list = (1, 2) x 1000;
ok(!(scalar(@list) != 2000), '(1, 2) x 1000 produces a list with 2000 elements');

# Unary minus

# Test unary minus with plain strings and numbers

# Test with plain strings
$result = -("+");
ok(!($result ne "-"), 'unary minus on + results in -');

$result = -("-");
ok(!($result ne "+"), 'unary minus on - results in +');

$result = -("-aa");
ok(!($result ne "+aa"), 'unary minus on -aa results in +aa <$result>');

$result = -("a");
ok(!($result ne "-a"), 'unary minus on a results in -a');

$result = -("aa");
ok(!($result ne "-aa"), 'unary minus on aa results in -aa');

# Test with strings starting with numbers
$result = -("0aa");
ok(!($result ne "0"), 'unary minus on 0aa results in 0 <$result>');

$result = -("12aa");
ok(!($result ne "-12"), 'unary minus on 12aa results in -12');

# Test with non-numeric strings
$result = -("abc");
ok(!($result ne "-abc"), 'unary minus on abc results in -abc');

$result = -("-abc");
ok(!($result ne "+abc"), 'unary minus on -abc results in +abc');

# Test with edge cases and invalid formats
$result = -("");
ok(!($result ne "0"), 'unary minus on  results in 0');

$result = -("123");
ok(!($result ne "-123"), 'unary minus on 123 results in -123');

###################
# Unary Plus Operator

# Unary plus on a positive number
my $b = +5;
ok(!($b != 5), '+5 equals 5');

# Unary plus on a negative number
$b = +-5;
ok(!($b != -5), '+-5 equals -5');

###################
# Increment and Decrement Operators

# Pre-increment
$b = 5;
++$b;
ok(!($b != 6), '++5 equals 6');

# Post-increment
$b = 5;
$b++;
ok(!($b != 6), '5++ equals 6');

# Pre-decrement
$b = 5;
--$b;
ok(!($b != 4), '--5 equals 4');

# Post-decrement
$b = 5;
$b--;
ok(!($b != 4), '5-- equals 4');

###################
# Floating-point Arithmetic

# Floating-point addition
my $c = 5.5 + 2.5;
ok(!($c != 8.0), '5.5 + 2.5 equals 8.0');

# Floating-point subtraction
$c = 5.5 - 2.5;
ok(!($c != 3.0), '5.5 - 2.5 equals 3.0');

# Floating-point multiplication
$c = 2.5 * 2.0;
ok(!($c != 5.0), '2.5 * 2.0 equals 5.0');

# Floating-point division
$c = 5.0 / 2.0;
ok(!($c != 2.5), '5.0 / 2.0 equals 2.5');

###################
# Integer Division

# Integer division
$c = int(5 / 2);
ok(!($c != 2), 'int(5 / 2) equals 2');

###################
# Trigonometric Functions

# Sine of 0
$c = sin(0);
ok(!($c != 0), 'sin(0) equals 0');

# Cosine of 0
$c = cos(0);
ok(!($c != 1), 'cos(0) equals 1');

###################
# Square Root and Logarithms

# Square root
$c = sqrt(9);
ok(!($c != 3), 'sqrt(9) equals 3');

# Natural logarithm
$c = log(exp(1));
ok(!($c != 1), 'log(exp(1)) equals 1');

###################
# Absolute Value

# Absolute value of a negative number
$c = abs(-5);
ok(!($c != 5), 'abs(-5) equals 5');

###################
# Random Number Generation

# Random number generation
$c = rand();
ok(!($c < 0 || $c >= 1), 'rand() generates a number between 0 and 1');
###################
# Arctangent Function (atan2)

# Arctangent of (1, 1) (should be π/4 or approximately 0.785398)
$c = atan2(1, 1);
ok(!(abs($c - 0.785398) > 0.000001), 'atan2(1, 1) is approximately 0.785398 (π/4)');

# Arctangent of (0, 1) (should be 0)
$c = atan2(0, 1);
ok(!(abs($c - 0) > 0.000001), 'atan2(0, 1) is 0');

# Arctangent of (1, 0) (should be π/2 or approximately 1.570796)
$c = atan2(1, 0);
ok(!(abs($c - 1.570796) > 0.000001), 'atan2(1, 0) is approximately 1.570796 (π/2)');

# Arctangent of (-1, -1) (should be -3π/4 or approximately -2.356194)
$c = atan2(-1, -1);
ok(!(abs($c + 2.356194) > 0.000001), 'atan2(-1, -1) is approximately -2.356194 (-3π/4)');

done_testing();
