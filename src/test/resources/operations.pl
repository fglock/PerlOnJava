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

###################
# Arithmetic Operators

# Addition
my $a = 5 + 3;
print "not " if $a != 8; say "ok # 5 + 3 equals 8";

# Subtraction
$a = 10 - 2;
print "not " if $a != 8; say "ok # 10 - 2 equals 8";

# Multiplication
$a = 4 * 2;
print "not " if $a != 8; say "ok # 4 * 2 equals 8";

# Division
$a = 16 / 2;
print "not " if $a != 8; say "ok # 16 / 2 equals 8";

# Modulus
$a = 10 % 3;
print "not " if $a != 1; say "ok # 10 % 3 equals 1";

# Exponentiation
$a = 2 ** 3;
print "not " if $a != 8; say "ok # 2 ** 3 equals 8";


###################
# Comparison Operators

# Numeric Equality
$a = 5 == 5;
print "not " if !$a; say "ok # 5 == 5 is true";

# Numeric Inequality
$a = 5 != 4;
print "not " if !$a; say "ok # 5 != 4 is true";

# Greater Than
$a = 10 > 5;
print "not " if !$a; say "ok # 10 > 5 is true";

# Less Than
$a = 3 < 8;
print "not " if !$a; say "ok # 3 < 8 is true";

# Greater Than or Equal To
$a = 7 >= 7;
print "not " if !$a; say "ok # 7 >= 7 is true";

# Less Than or Equal To
$a = 6 <= 6;
print "not " if !$a; say "ok # 6 <= 6 is true";


###################
# String Operators

# String Concatenation
my $str = "Hello, " . "world!";
print "not " if $str ne "Hello, world!"; say "ok # String concatenation 'Hello, world!'";

# String Equality
$a = "foo" eq "foo";
print "not " if !$a; say "ok # 'foo' eq 'foo' is true";

# String Inequality
$a = "foo" ne "bar";
print "not " if !$a; say "ok # 'foo' ne 'bar' is true";

# String Greater Than
$a = "abc" gt "abb";
print "not " if !$a; say "ok # 'abc' gt 'abb' is true";

# String Less Than
$a = "abc" lt "abd";
print "not " if !$a; say "ok # 'abc' lt 'abd' is true";


###################
# Substring Tests

# Substring with positive offset
$str = "Hello, World!";
my $substr = substr($str, 7);
print "not " if $substr ne "World!"; say "ok # substr('Hello, World!', 7)";

# Substring with positive offset and length
$substr = substr($str, 7, 5);
print "not " if $substr ne "World"; say "ok # substr('Hello, World!', 7, 5)";

# Substring with negative offset
$substr = substr($str, -6);
print "not " if $substr ne "World!"; say "ok # substr('Hello, World!', -6)";

# Substring with negative offset and length
$substr = substr($str, -6, 5);
print "not " if $substr ne "World"; say "ok # substr('Hello, World!', -6, 5)";

# Substring with offset out of bounds
$substr = substr($str, 20);
print "not " if $substr ne ""; say "ok # substr('Hello, World!', 20)";

# Substring with length out of bounds
$substr = substr($str, 7, 20);
print "not " if $substr ne "World!"; say "ok # substr('Hello, World!', 7, 20)";

# Substring with negative length
$substr = substr($str, 7, -1);
print "not " if $substr ne "World"; say "ok # substr('Hello, World!', 7, -1)";


###################
# sprintf tests

# Test integer formatting
my $int = sprintf("%d", 42);
print "not " if $int ne "42"; say "ok # sprintf('%d', 42)";

# Test floating-point formatting
my $float = sprintf("%.2f", 3.14159);
print "not " if $float ne "3.14"; say "ok # sprintf('%.2f', 3.14159)";

# Test string formatting
my $str = sprintf("%s", "Hello, World!");
print "not " if $str ne "Hello, World!"; say "ok # sprintf('%s', 'Hello, World!')";

# Test multiple arguments
my $formatted = sprintf("Name: %s, Age: %d", "Alice", 30);
print "not " if $formatted ne "Name: Alice, Age: 30"; say "ok # sprintf('Name: %s, Age: %d', 'Alice', 30)";

# Test padding and width
my $padded = sprintf("|%10s|", "test");
print "not " if $padded ne "|      test|"; say "ok # sprintf('|%10s|', 'test') |$padded|";

# Test negative width
my $negative_width = sprintf("|%-10s|", "test");
print "not " if $negative_width ne "|test      |"; say "ok # sprintf('|%-10s|', 'test') |$negative_width|";

# Test invalid format string
my $invalid_format;
eval { $invalid_format = sprintf("%z", "data") };
print "not " if $@; # Expecting an error
print "ok # sprintf with invalid format string causes an error\n";

###################
# Logical Operators

# Logical AND
$a = 1 && 1;
print "not " if !$a; say "ok # 1 && 1 is true";

# Logical OR
$a = 0 || 1;
print "not " if !$a; say "ok # 0 || 1 is true";

# Logical NOT
$a = !0;
print "not " if !$a; say "ok # !0 is true";

###################
# Bitwise Operators

# Bitwise AND
$a = 5 & 3;
print "not " if $a != 1; say "ok # 5 & 3 equals 1";

# Bitwise OR
$a = 5 | 3;
print "not " if $a != 7; say "ok # 5 | 3 equals 7";

# Bitwise XOR
$a = 5 ^ 3;
print "not " if $a != 6; say "ok # 5 ^ 3 equals 6";

# Bitwise NOT
$a = ~5;
print "not " if $a != -6; say "ok # ~5 equals -6";

# Left Shift
$a = 5 << 1;
print "not " if $a != 10; say "ok # 5 << 1 equals 10";

# Right Shift
$a = 5 >> 1;
print "not " if $a != 2; say "ok # 5 >> 1 equals 2";

###################
# Compound Assignment Operators

# Addition Assignment
$a = 5;
$a += 3;
print "not " if $a != 8; say "ok # 5 += 3 equals 8";

# Subtraction Assignment
$a = 10;
$a -= 2;
print "not " if $a != 8; say "ok # 10 -= 2 equals 8";

# Multiplication Assignment
$a = 4;
$a *= 2;
print "not " if $a != 8; say "ok # 4 *= 2 equals 8";

# Division Assignment
$a = 16;
$a /= 2;
print "not " if $a != 8; say "ok # 16 /= 2 equals 8";

# Modulus Assignment
$a = 10;
$a %= 3;
print "not " if $a != 1; say "ok # 10 %= 3 equals 1";

# Exponentiation Assignment
$a = 2;
$a **= 3;
print "not " if $a != 8; say "ok # 2 **= 3 equals 8";

# Bitwise AND Assignment
$a = 12; # 1100 in binary
$a &= 10; # 1010 in binary
print "not " if $a != 8; say "ok # 12 &= 10 equals 8";

# Bitwise OR Assignment
$a = 5; # 0101 in binary
$a |= 3; # 0011 in binary
print "not " if $a != 7; say "ok # 5 |= 3 equals 7";

# Bitwise XOR Assignment
$a = 5; # 0101 in binary
$a ^= 3; # 0011 in binary
print "not " if $a != 6; say "ok # 5 ^= 3 equals 6";

# Bitwise Shift Left Assignment
$a = 2; # 0010 in binary
$a <<= 2; # Shift left by 2 bits
print "not " if $a != 8; say "ok # 2 <<= 2 equals 8";

# Bitwise Shift Right Assignment
$a = 8; # 1000 in binary
$a >>= 2; # Shift right by 2 bits
print "not " if $a != 2; say "ok # 8 >>= 2 equals 2";
