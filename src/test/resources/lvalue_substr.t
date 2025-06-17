use strict;
use warnings;
use Test::More tests => 12;

# Test basic substring assignment
my $str = "Hello, world!";
substr($str, 0, 5) = "Greetings";
is($str, "Greetings, world!", "Basic substring assignment");

# Test assignment beyond string length
$str = "Short";
my $error = eval {
    substr($str, 10, 5) = "long";
    1;
} ? "" : $@;
like($error, qr/substr outside of string/, "Assignment beyond string length throws correct error");

# Test assignment with negative offset
$str = "Hello, world!";
substr($str, -6, 5) = "there";
is($str, "Hello, there!", "Assignment with negative offset");

# Test assignment with zero length
$str = "Insert";
substr($str, 3, 0) = " here";
is($str, "Ins hereert", "Assignment with zero length");

# Test assignment shorter than original substring
$str = "Long substring";
substr($str, 5, 9) = "str";
is($str, "Long str", "Assignment shorter than original");

# Test assignment longer than original substring
$str = "Expand";
substr($str, 1, 3) = "xtend";
is($str, "Extendnd", "Assignment longer than original");

# Test assignment with negative length
$str = "Negative";
substr($str, 3, -2) = "positive";
is($str, "Negpositiveve", "Assignment with negative length");

# Test chained assignments
$str = "Chain";
substr(substr($str, 0, 3), 1, 1) = "u";
is($str, "Cuain", "Chained assignments");

# Test assignment at the very end
$str = "Append";
substr($str, 6, 0) = " text";
is($str, "Append text", "Assignment at the end");

# Test assignment with unicode characters
$str = "Unicode \x{263A} test";
substr($str, 8, 1) = "\x{2665}";
my $expected = "Unicode \x{2665} test";
is($str, $expected, "Assignment with unicode characters");

# Test empty string assignment
$str = "Remove";
substr($str, 2, 2) = "";
is($str, "Reve", "Empty string assignment");

# Test assignment to empty string
$str = "";
substr($str, 0, 0) = "New";
is($str, "New", "Assignment to empty string");
