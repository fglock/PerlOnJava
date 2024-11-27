use feature 'say';
use strict;
use warnings;

###################
# Perl split() Function Tests

# Test case 1: Pattern is omitted or a single space character
my $string = "  This  is   a test  string  ";
my $pattern = ' ';
my @result = split($pattern, $string);
my @expected = ('This', 'is', 'a', 'test', 'string');
my $match = "@result" eq "@expected";
print "not " if @result != 5; say "ok # Pattern is a single space character: " . scalar(@result);
print "not " if !$match; say "ok # Pattern is a single space character: <", join(",", @result), ">";

# Test case 2: Pattern is /^/ with multiline modifier
$string = "This\nis\na\ntest\nstring";
$pattern = '^';
@result = split(/$pattern/m, $string);
@expected = ("This\n", "is\n", "a\n", "test\n", "string");
$match = "@result" eq "@expected";
print "not " if scalar(@result) != 5; say "ok # Pattern /^/ with multiline modifier: " . scalar(@result);
print "not " if !$match; say "ok # Pattern /^/ with multiline modifier"; #  : <", join(",", @result), ">";

# Test case 3: Pattern with capturing groups
$string = 'a1b2c3';
$pattern = '(\d+)';
@result = split(/$pattern/, $string);
@expected = ('a', '1', 'b', '2', 'c', '3');
$match = "@result" eq "@expected";
print "not " if !$match; say "ok # Pattern with capturing groups <", join(",", @result), ">";

# Test case 4: Negative limit
$string = 'This is a test string';
$pattern = ' ';
@result = split($pattern, $string, -1);
@expected = ('This', 'is', 'a', 'test', 'string');
$match = "@result" eq "@expected";
print "not " if !$match; say "ok # Negative limit";

# Test case 5: Omitted or zero limit with trailing empty fields
$string = 'This is a test string ';
$pattern = ' ';
@result = split($pattern, $string);
@expected = ('This', 'is', 'a', 'test', 'string');
$match = "@result" eq "@expected";
print "not " if !$match; say "ok # Omitted or zero limit with trailing empty fields";

# Test case 6: Empty pattern (split between characters)
$string = 'abc';
$pattern = '';
@result = split(/$pattern/, $string);
@expected = ('a', 'b', 'c');
$match = "@result" eq "@expected";
print "not " if !$match; say "ok # Empty pattern (split between characters)";

# Test case 7: Literal string pattern
$string = 'a,b,c';
$pattern = ',';
@result = split($pattern, $string);
@expected = ('a', 'b', 'c');
$match = "@result" eq "@expected";
print "not " if !$match; say "ok # Literal string pattern";

# Test case 8: Pattern with no capturing groups and limit zero
$string = 'a b c ';
$pattern = ' ';
@result = split($pattern, $string, 0);
@expected = ('a', 'b', 'c');
$match = "@result" eq "@expected";
print "not " if !$match; say "ok # Pattern with no capturing groups and limit zero";

# Test case 9: Pattern with capturing groups and limit zero
$string = 'a1b2c3';
$pattern = '(\d+)';
@result = split(/$pattern/, $string, 0);
@expected = ('a', '1', 'b', '2', 'c', '3');
$match = "@result" eq "@expected";
print "not " if !$match; say "ok # Pattern with capturing groups and limit zero";

# Test case 10: Pattern with capturing groups and positive limit
$string = 'a1b2c3';
$pattern = '(\d+)';
@result = split(/$pattern/, $string, 3);
@expected = ('a', '1', 'b', '2', 'c3');
$match = "@result" eq "@expected";
print "not " if !$match; say "ok # Pattern with capturing groups and positive limit: <", join(",", @result), ">";
