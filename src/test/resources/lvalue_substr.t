use strict;
use feature 'say';

# Test basic substring assignment
my $str = "Hello, world!";
substr($str, 0, 5) = "Greetings";
my $result = $str;
print "not " if $str ne "Greetings, world!";
say "ok # Basic substring assignment <$result>";

# Test assignment beyond string length (should emit error)
$str = "Short";
my $error = eval {
    substr($str, 10, 5) = "long";
    1;
} ? "" : $@;
$result = $error =~ /substr outside of string/ ? "Error caught: " . substr($error, 0, 30) . "..." : "No error or unexpected error";
print "not " if $error !~ /substr outside of string/;
say "ok # Assignment beyond string length (error check) <$result>";

# Test assignment with negative offset
$str = "Hello, world!";
substr($str, -6, 5) = "there";
$result = $str;
print "not " if $str ne "Hello, there!";
say "ok # Assignment with negative offset <$result>";

# Test assignment with zero length
$str = "Insert";
substr($str, 3, 0) = " here";
$result = $str;
print "not " if $str ne "Ins hereert";
say "ok # Assignment with zero length <$result>";

# Test assignment shorter than original substring
$str = "Long substring";
substr($str, 5, 9) = "str";
$result = $str;
print "not " if $str ne "Long str";
say "ok # Assignment shorter than original <$result>";

# Test assignment longer than original substring
$str = "Expand";
substr($str, 1, 3) = "xtend";
$result = $str;
print "not " if $str ne "Extendnd";
say "ok # Assignment longer than original <$result>";

# Test assignment with negative length (treated as 0)
$str = "Negative";
substr($str, 3, -2) = "positive";
$result = $str;
print "not " if $str ne "Negpositiveve";
say "ok # Assignment with negative length <$result>";

# Test chained assignments
$str = "Chain";
substr(substr($str, 0, 3), 1, 1) = "u";
$result = $str;
print "not " if $str ne "Cuain";
say "ok # Chained assignments <$result>";

# Test assignment at the very end
$str = "Append";
substr($str, 6, 0) = " text";
$result = $str;
print "not " if $str ne "Append text";
say "ok # Assignment at the end <$result>";

# Test assignment with unicode characters
# use utf8;
# binmode(STDOUT, ":utf8");
$str = "Unicode \x{263A} test";  # Unicode smiley face
substr($str, 8, 1) = "\x{2665}"; # Unicode heart symbol
$result = $str;
my $expected = "Unicode \x{2665} test";
print "not " if $str ne $expected;
say "ok # Assignment with unicode characters <$result>";

# Test empty string assignment
$str = "Remove";
substr($str, 2, 2) = "";
$result = $str;
print "not " if $str ne "Reve";
say "ok # Empty string assignment <$result>";

# Test assignment to empty string
$str = "";
substr($str, 0, 0) = "New";
$result = $str;
print "not " if $str ne "New";
say "ok # Assignment to empty string <$result>";
