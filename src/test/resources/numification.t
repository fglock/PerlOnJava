use strict;
use Test::More;
use feature 'say';

# Test basic integer numification
my $x = "42";
is(0 + $x, 42, "Basic integer numification");

# Test floating point numification
$x = "3.14";
cmp_ok(abs((0 + $x) - 3.14), '<', 0.0001, "Floating point numification");

# Test leading/trailing whitespace
$x = "  123  ";
is(0 + $x, 123, "Whitespace handling");

# Test hexadecimal
$x = "0xFF";
is(0 + $x, 0, "Hexadecimal numification");

# Test octal
$x = "0755";
is(0 + $x, 755, "Octal numification");

# Test binary
$x = "0b1010";
is(0 + $x, 0, "Binary numification");

# Test scientific notation
$x = "1.5e3";
is(0 + $x, 1500, "Scientific notation");

# Test string with non-numeric suffix
$x = "42foo";
is(0 + $x, 42, "Non-numeric suffix");

# Test string with multiple decimal points
$x = "3.14.15";
is(0 + $x, 3.14, "Multiple decimal points");

# Test string with invalid exponent
$x = "1e+";
is(0 + $x, 1, "Invalid exponent");

# Test empty string
$x = "";
is(0 + $x, 0, "Empty string");

# Test string with only whitespace
$x = "   ";
is(0 + $x, 0, "Whitespace only");

# Test string with leading plus sign
$x = "+42";
is(0 + $x, 42, "Leading plus sign");

# Test string with leading minus sign
$x = "-42";
is(0 + $x, -42, "Leading minus sign");

# Test string with underscore
$x = "1_000";
is(0 + $x, 1, "Underscore in number");

# Test string with comma
$x = "1,000";
is(0 + $x, 1, "Comma in number");

# Test very large number
$x = "1" . "0" x 20;
is(0 + $x, 1e20, "Very large number");

# Test very small number
$x = "1e-20";
cmp_ok(abs((0 + $x) - 1e-20), '<', 1e-25, "Very small number");

done_testing();
