use strict;
use Test::More;
use feature 'say';

# Test positive infinity
my $x = "Inf";
ok(!((0 + $x) != "Inf"), 'Positive infinity');
ok(!((-$x) != "-Inf"), 'Negative infinity');
ok(!((0 - $x) != "-Inf"), 'Negative infinity');

# Test negative infinity
$x = "-Inf";
ok(!((0 + $x) != "-Inf"), 'Negative infinity');

# Test NaN (Not a Number)
$x = "NaN";
ok(!((0 + $x) == (0 + $x)), 'NaN (Not a Number)');

# Test case-insensitivity for Inf
$x = "inf";
ok(!((0 + $x) != "Inf"), 'Case-insensitive positive infinity');

# Test case-insensitivity for -Inf
$x = "-inf";
ok(!((0 + $x) != "-Inf"), 'Case-insensitive negative infinity');

# Test case-insensitivity for NaN
$x = "nan";
ok(!((0 + $x) == (0 + $x)), 'Case-insensitive NaN');

done_testing();
