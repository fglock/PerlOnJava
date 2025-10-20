use strict;
use Test::More;
use warnings;
use feature 'say';

###################
# Perl Atomic Group Tests

# Basic atomic group test - demonstrates preventing backtracking
my $string = "catastrophe";
my $pattern = qr/(?>cata|cat)astrophe/;  # Fails because 'cata' is committed to
my $match = $string =~ $pattern;
ok(!$match, "'catastrophe' does not match '(?>cats|cat)astrophe'");

# Compare with non-atomic group
$string = "catastrophe";
$pattern = qr/(cata|cat)astrophe/;  # Succeed because 'cata' is not committed to
$match = $string =~ $pattern;
ok($match, "'catastrophe' does not match '(?>cats|cat)astrophe'");

# Test atomic group with repetition
$string = "aaaaa";
$pattern = qr/(?>a+)a/;  # Fails because a+ takes all 'a's
$match = $string =~ $pattern;
ok(!$match, "'aaaaa' does not match '(?>a+)a'");

# Compare with non-atomic repetition
$string = "aaaaa";
$pattern = qr/(a+)a/;  # Succeeds because it can backtrack
$match = $string =~ $pattern;
ok($match, "'aaaaa' matches '(a+)a'");

# Test atomic group with word boundaries
$string = "the cat";
$pattern = qr/\b(?>cat|category)\b/;  # Matches 'cat' as a word
$match = $string =~ $pattern;
ok($match, "'the cat' matches '\\b(?>cat|category)\\b'");

# Test atomic group failing with word boundaries
$string = "category";
$pattern = qr/\b(?>cat|category)\b/;  # Fails because 'cat' is committed to
$match = $string =~ $pattern;
ok(!$match, "'category' does not match '\\b(?>cat|category)\\b'");

# Test atomic group with alternation
$string = "handle";
$pattern = qr/(?>hand|handle)s/;  # Fails because 'hand' is committed to
$match = $string =~ $pattern;
ok(!$match, "'handle' does not match '(?>hand|handle)s'");

# Test atomic group with nested groups
$string = "foo123bar";
$pattern = qr/(?>foo(\d+))bar/;  # Matches - atomic group includes the digits
$match = $string =~ $pattern;
ok($match, "'foo123bar' matches '(?>foo(\\d+))bar'");

# Test atomic group with lookahead
$string = "foobar";
$pattern = qr/(?>foo(?=bar))bar/;  # Matches - lookahead doesn't affect atomicity
$match = $string =~ $pattern;
ok($match, "'foobar' matches '(?>foo(?=bar))bar'");

# Test atomic group in a longer pattern
$string = "The quick brown fox";
$pattern = qr/\b(?>quick|quickly)\b brown/;  # Matches 'quick brown'
$match = $string =~ $pattern;
ok($match, "'The quick brown fox' matches '\\b(?>quick|quickly)\\b brown'");

# Test nested atomic groups
$string = "abcdef";
$pattern = qr/(?>ab(?>cd))ef/;  # Matches - nested atomic groups work together
$match = $string =~ $pattern;
ok($match, "'abcdef' matches '(?>ab(?>cd))ef'");

# Test atomic group with character class
$string = "12345";
$pattern = qr/(?>[\d]+)5/;  # Fails because atomic group takes all digits
$match = $string =~ $pattern;
ok(!$match, "'12345' does not match '(?>[\\d]+)5'");

# Test atomic group with optional elements
$string = "abc";
$pattern = qr/(?>ab?)c/;  # Matches because atomic group makes right choice
$match = $string =~ $pattern;
ok($match, "'abc' matches '(?>ab?)c'");

# Performance test case (commented out to avoid timing dependencies)
# This would demonstrate how atomic groups can improve performance by preventing
# unnecessary backtracking in cases where it would never succeed
#$string = "a" x 100 . "b";
#$pattern = qr/(?>a*)b/;

done_testing();
