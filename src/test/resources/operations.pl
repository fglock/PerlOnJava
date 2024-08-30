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

