#
#   This Perl script is part of the project's examples and demonstrates Compound Assignment operators.
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
# Compound Assignment Operators

# Addition Assignment
my $a = 5;
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
$a **= 3;
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

# String Concatenation Assignment
my $str = "Hello";
$str .= ", World!";
ok(!($str ne "Hello, World!"), '\'Hello\' .= \', World!\' equals \'Hello, World!\'');

# Repeat Assignment
$str = "a";
$str x= 3;
ok(!($str ne "aaa"), '\'a\' x= 3 equals \'aaa\'');

# Logical AND Assignment
$a = 1;
$a &&= 0;
ok(!($a != 0), '1 &&= 0 equals 0');

# Logical OR Assignment
$a = 0;
$a ||= 1;
ok(!($a != 1), '0 ||= 1 equals 1');

# Defined-or Assignment
my $undefined;
$undefined //= "default";
ok(!($undefined ne "default"), 'undefined //= \'default\' equals \'default\'');

done_testing();
