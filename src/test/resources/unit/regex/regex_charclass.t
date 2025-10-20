use strict;
use Test::More;
use feature 'say';

###################
# Perl Character Class Tests

# Test for [:print:] character class
my $string = "Hello, World! 123";
my $pattern = qr/[[:print:]]+/;
my $match = $string =~ $pattern;
ok($match, '\'Hello, World! 123\' matches \'[[:print:]]+\'');

# Test for [:digit:] character class
$string = "abc 123 def";
$pattern = qr/[[:digit:]]+/;
$match = $string =~ $pattern;
ok($match, '\'abc 123 def\' matches \'[[:digit:]]+\'');

# Test for [:alpha:] character class
$string = "abc 123 def";
$pattern = qr/[[:alpha:]]+/;
$match = $string =~ $pattern;
ok($match, '\'abc 123 def\' matches \'[[:alpha:]]+\'');

# Test for [:alnum:] character class
$string = "abc123";
$pattern = qr/[[:alnum:]]+/;
$match = $string =~ $pattern;
ok($match, '\'abc123\' matches \'[[:alnum:]]+\'');

# Test for [:space:] character class
$string = "abc def";
$pattern = qr/[[:space:]]+/;
$match = $string =~ $pattern;
ok($match, '\'abc def\' matches \'[[:space:]]+\'');

# Test for [:punct:] character class
$string = "Hello, World!";
$pattern = qr/[[:punct:]]+/;
$match = $string =~ $pattern;
ok($match, '\'Hello, World!\' matches \'[[:punct:]]+\'');

done_testing();
