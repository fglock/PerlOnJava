use strict;
use Test::More;
use feature 'say';

###################
# Perl Caret Modifier Tests

# Test for (?^i:) case insensitive negation
my $string = "Hello HELLO hello";
my $pattern = qr/(?^i:HELLO)/;
my $match = $string =~ $pattern;
ok($match, '\'HELLO\' matches case-sensitive \'(?^i:HELLO)\'');

# Test for (?^i:) with mixed case
$string = "HelloWorld HELLOWORLD";
$pattern = qr/hello(?^i:WORLD)/;
$match = $string =~ $pattern;
ok(!($match), '\'HelloWorld\' does not match \'hello(?^i:WORLD)\'');

# Test for multiple modifiers (?^im:)
$string = "Hello\nWorld";
$pattern = qr/(?^im:hello.*world)/;
$match = $string =~ $pattern;
ok(!($match), '\'Hello\\nWorld\' does not match multiline case-insensitive negated \'(?^im:hello.*world)\'');

# Test for nested modifiers
$string = "TEST test Test";
$pattern = qr/(?^i:test(?^:TEST))/;
$match = $string =~ $pattern;
ok(!($match), '\'TEST test Test\' does not match nested negated modifiers \'(?^i:test(?^:TEST))\'');

done_testing();
