use strict;
use feature 'say';

# Test basic integer numification
my $x = "42";
print "not " if (0 + $x) != 42; say "ok # Basic integer numification";

# Test floating point numification
$x = "3.14";
print "not " if abs((0 + $x) - 3.14) > 0.0001; say "ok # Floating point numification";

# Test leading/trailing whitespace
$x = "  123  ";
print "not " if (0 + $x) != 123; say "ok # Whitespace handling";

# Test hexadecimal
$x = "0xFF";
print "not " if (0 + $x) != 0; say "ok # Hexadecimal numification <@{[ 0 + $x ]}>";

# Test octal
$x = "0755";
print "not " if (0 + $x) != 755; say "ok # Octal numification <@{[ 0 + $x ]}>";

# Test binary
$x = "0b1010";
print "not " if (0 + $x) != 0; say "ok # Binary numification <@{[ 0 + $x ]}>";

# Test scientific notation
$x = "1.5e3";
print "not " if (0 + $x) != 1500; say "ok # Scientific notation";

# Test string with non-numeric suffix
$x = "42foo";
print "not " if (0 + $x) != 42; say "ok # Non-numeric suffix";

# Test string with multiple decimal points
$x = "3.14.15";
print "not " if (0 + $x) != 3.14; say "ok # Multiple decimal points";

# Test string with invalid exponent
$x = "1e+";
print "not " if (0 + $x) != 1; say "ok # Invalid exponent";

# Test empty string
$x = "";
print "not " if (0 + $x) != 0; say "ok # Empty string";

# Test string with only whitespace
$x = "   ";
print "not " if (0 + $x) != 0; say "ok # Whitespace only";

# Test string with leading plus sign
$x = "+42";
print "not " if (0 + $x) != 42; say "ok # Leading plus sign";

# Test string with leading minus sign
$x = "-42";
print "not " if (0 + $x) != -42; say "ok # Leading minus sign <@{[ 0 + $x ]}>";

# Test string with underscore
$x = "1_000";
print "not " if (0 + $x) != 1; say "ok # Underscore in number <@{[ 0 + $x ]}>";

# Test string with comma (should not be treated as part of the number)
$x = "1,000";
print "not " if (0 + $x) != 1; say "ok # Comma in number";

# Test very large number
$x = "1" . "0" x 20;
print "not " if (0 + $x) != 1e20; say "ok # Very large number <$x> <@{[ 0 + $x ]}>";

# Test very small number
$x = "1e-20";
print "not " if abs((0 + $x) - 1e-20) > 1e-25; say "ok # Very small number <@{[ 0 + $x ]}>";

