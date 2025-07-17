use strict;
use feature 'say';

print "1..4\n";

###################
# Perl Caret Modifier Tests

# Test for (?^i:) case insensitive negation
my $string = "Hello HELLO hello";
my $pattern = qr/(?^i:HELLO)/;
my $match = $string =~ $pattern;
print "not " if !$match; say "ok # 'HELLO' matches case-sensitive '(?^i:HELLO)'";

# Test for (?^i:) with mixed case
$string = "HelloWorld HELLOWORLD";
$pattern = qr/hello(?^i:WORLD)/;
$match = $string =~ $pattern;
print "not " if $match; say "ok # 'HelloWorld' does not match 'hello(?^i:WORLD)'";

# Test for multiple modifiers (?^im:)
$string = "Hello\nWorld";
$pattern = qr/(?^im:hello.*world)/;
$match = $string =~ $pattern;
print "not " if $match; say "ok # 'Hello\\nWorld' does not match multiline case-insensitive negated '(?^im:hello.*world)'";

# Test for nested modifiers
$string = "TEST test Test";
$pattern = qr/(?^i:test(?^:TEST))/;
$match = $string =~ $pattern;
print "not " if $match; say "ok # 'TEST test Test' does not match nested negated modifiers '(?^i:test(?^:TEST))'";

