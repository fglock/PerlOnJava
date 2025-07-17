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
use feature "say";

print "1..16\n";

###################
# Compound Assignment Operators

# Addition Assignment
my $a = 5;
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
$a **= 3;
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

# String Concatenation Assignment
my $str = "Hello";
$str .= ", World!";
print "not " if $str ne "Hello, World!";
say "ok # 'Hello' .= ', World!' equals 'Hello, World!'";

# Repeat Assignment
$str = "a";
$str x= 3;
print "not " if $str ne "aaa";
say "ok # 'a' x= 3 equals 'aaa'";

# Logical AND Assignment
$a = 1;
$a &&= 0;
print "not " if $a != 0;
say "ok # 1 &&= 0 equals 0";

# Logical OR Assignment
$a = 0;
$a ||= 1;
print "not " if $a != 1;
say "ok # 0 ||= 1 equals 1";

# Defined-or Assignment
my $undefined;
$undefined //= "default";
print "not " if $undefined ne "default";
say "ok # undefined //= 'default' equals 'default'";


