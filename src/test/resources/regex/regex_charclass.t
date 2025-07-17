use strict;
use feature 'say';

print "1..6\n";

###################
# Perl Character Class Tests

# Test for [:print:] character class
my $string = "Hello, World! 123";
my $pattern = qr/[[:print:]]+/;
my $match = $string =~ $pattern;
print "not " if !$match; say "ok # 'Hello, World! 123' matches '[[:print:]]+'";

# Test for [:digit:] character class
$string = "abc 123 def";
$pattern = qr/[[:digit:]]+/;
$match = $string =~ $pattern;
print "not " if !$match; say "ok # 'abc 123 def' matches '[[:digit:]]+'";

# Test for [:alpha:] character class
$string = "abc 123 def";
$pattern = qr/[[:alpha:]]+/;
$match = $string =~ $pattern;
print "not " if !$match; say "ok # 'abc 123 def' matches '[[:alpha:]]+'";

# Test for [:alnum:] character class
$string = "abc123";
$pattern = qr/[[:alnum:]]+/;
$match = $string =~ $pattern;
print "not " if !$match; say "ok # 'abc123' matches '[[:alnum:]]+'";

# Test for [:space:] character class
$string = "abc def";
$pattern = qr/[[:space:]]+/;
$match = $string =~ $pattern;
print "not " if !$match; say "ok # 'abc def' matches '[[:space:]]+'";

# Test for [:punct:] character class
$string = "Hello, World!";
$pattern = qr/[[:punct:]]+/;
$match = $string =~ $pattern;
print "not " if !$match; say "ok # 'Hello, World!' matches '[[:punct:]]+'";

