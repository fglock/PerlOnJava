use strict;
use feature 'say';

# Test positive infinity
my $x = "Inf";
print "not " if (0 + $x) != "Inf"; say "ok # Positive infinity";

# Test negative infinity
$x = "-Inf";
print "not " if (0 + $x) != "-Inf"; say "ok # Negative infinity";

# Test NaN (Not a Number)
$x = "NaN";
print "not " if (0 + $x) == (0 + $x); say "ok # NaN (Not a Number)";

# Test case-insensitivity for Inf
$x = "inf";
print "not " if (0 + $x) != "Inf"; say "ok # Case-insensitive positive infinity";

# Test case-insensitivity for -Inf
$x = "-inf";
print "not " if (0 + $x) != "-Inf"; say "ok # Case-insensitive negative infinity";

# Test case-insensitivity for NaN
$x = "nan";
print "not " if (0 + $x) == (0 + $x); say "ok # Case-insensitive NaN";

