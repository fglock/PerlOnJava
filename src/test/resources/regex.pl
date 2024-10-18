use strict;
use feature 'say';

###################
# Perl m// Operator Tests

# Simple match
my $string = "Hello World";
my $pattern = qr/World/;
my $match = $string =~ $pattern;
print "not " if !$match; say "ok # 'Hello World' matches 'World'";

# Test special variables $`, $&, $'
print "not " if $` ne 'Hello '; say "ok # \$` is 'Hello ' <$`>";
print "not " if $& ne 'World'; say "ok # \$& is 'World' <$&>";
print "not " if $' ne ''; say "ok # \$' is '' <$'>";

# No match
$pattern = qr/Universe/;
$match = $string =~ $pattern;
print "not " if $match; say "ok # 'Hello World' does not match 'Universe'";

# Match with capture groups
$pattern = qr/(Hello) (World)/;
$match = $string =~ $pattern;
print "not " if !$match; say "ok # 'Hello World' matches '(Hello) (World)'";
print "not " if $1 ne 'Hello'; say "ok # \$1 is 'Hello' <$1>";
print "not " if $2 ne 'World'; say "ok # \$2 is 'World' <$2>";

# Test special variables $`, $&, $'
print "not " if $` ne ''; say "ok # \$` is '' <$`>";
print "not " if $& ne 'Hello World'; say "ok # \$& is 'Hello World' <$&>";
print "not " if $' ne ''; say "ok # \$' is '' <$'>";

# Match with multiple capture groups
$string = "foo bar baz";
$pattern = qr/(foo) (bar) (baz)/;
$match = $string =~ $pattern;
print "not " if !$match; say "ok # 'foo bar baz' matches '(foo) (bar) (baz)'";
print "not " if $1 ne 'foo'; say "ok # \$1 is 'foo'";
print "not " if $2 ne 'bar'; say "ok # \$2 is 'bar'";
print "not " if $3 ne 'baz'; say "ok # \$3 is 'baz'";

# Test special variables $`, $&, $'
print "not " if $` ne ''; say "ok # \$` is '' <$`>";
print "not " if $& ne 'foo bar baz'; say "ok # \$& is 'foo bar baz' <$&>";
print "not " if $' ne ''; say "ok # \$' is '' <$'>";

# Match with global flag
$string = "abc abc abc";
$pattern = qr/abc/;
my @matches = $string =~ /$pattern/g;
print "not " if scalar(@matches) != 3; say "ok # 'abc abc abc' matches 'abc' 3 times <@matches>";

# Match with case insensitive flag
$string = "Hello World";
$pattern = qr/hello/i;
$match = $string =~ $pattern;
print "not " if !$match; say "ok # 'Hello World' matches 'hello' case insensitively";

# Match with non-capturing group
$string = "foo bar baz";
$pattern = qr/(?:foo) (bar) (baz)/;
$match = $string =~ $pattern;
print "not " if !$match; say "ok # 'foo bar baz' matches '(?:foo) (bar) (baz)'";
print "not " if $1 ne 'bar'; say "ok # \$1 is 'bar'";
print "not " if $2 ne 'baz'; say "ok # \$2 is 'baz'";

# Match with lookahead
$string = "foo123";
$pattern = qr/foo(?=\d+)/;
$match = $string =~ $pattern;
print "not " if !$match; say "ok # 'foo123' matches 'foo' followed by digits";

# Match with lookbehind
$string = "123foo";
$pattern = qr/(?<=\d{2})foo/;
$match = $string =~ $pattern;
print "not " if !$match; say "ok # '123foo' matches 'foo' preceded by digits";

# Match with alternation
$string = "apple";
$pattern = qr/apple|orange/;
$match = $string =~ $pattern;
print "not " if !$match; say "ok # 'apple' matches 'apple' or 'orange'";

# Match with quantifiers
$string = "aaa";
$pattern = qr/a{3}/;
$match = $string =~ $pattern;
print "not " if !$match; say "ok # 'aaa' matches 'a{3}'";


###################
# Perl m// Operator Tests in List Context

# Simple match in list context
$string = "Hello World";
$pattern = qr/(Hello) (World)/;
@matches = $string =~ $pattern;
print "not " if scalar(@matches) != 2; say "ok # 'Hello World' matches '(Hello) (World)' in list context <" . scalar(@matches) . ">";
print "not " if $matches[0] ne 'Hello'; say "ok # \$matches[0] is 'Hello'";
print "not " if $matches[1] ne 'World'; say "ok # \$matches[1] is 'World'";

# Multiple matches in list context
$string = "foo bar baz";
$pattern = qr/(foo) (bar) (baz)/;
@matches = $string =~ $pattern;
print "not " if scalar(@matches) != 3; say "ok # 'foo bar baz' matches '(foo) (bar) (baz)' in list context";
print "not " if $matches[0] ne 'foo'; say "ok # \$matches[0] is 'foo'";
print "not " if $matches[1] ne 'bar'; say "ok # \$matches[1] is 'bar'";
print "not " if $matches[2] ne 'baz'; say "ok # \$matches[2] is 'baz'";

# Global match in list context
$string = "abc abc abc";
$pattern = qr/(abc)/;
@matches = $string =~ /$pattern/g;
print "not " if scalar(@matches) != 3; say "ok # 'abc abc abc' matches 'abc' 3 times in list context";
print "not " if $matches[0] ne 'abc'; say "ok # \$matches[0] is 'abc'";
print "not " if $matches[1] ne 'abc'; say "ok # \$matches[1] is 'abc'";
print "not " if $matches[2] ne 'abc'; say "ok # \$matches[2] is 'abc'";

# Match with capture groups and global flag in list context
$string = "foo1 bar2 baz3";
$pattern = qr/(\w+)(\d)/;
@matches = $string =~ /$pattern/g;
print "not " if scalar(@matches) != 6; say "ok # 'foo1 bar2 baz3' matches '(\\w+)(\\d)' 3 times in list context";
print "not " if $matches[0] ne 'foo'; say "ok # \$matches[0] is 'foo'";
print "not " if $matches[1] ne '1'; say "ok # \$matches[1] is '1'";
print "not " if $matches[2] ne 'bar'; say "ok # \$matches[2] is 'bar'";
print "not " if $matches[3] ne '2'; say "ok # \$matches[3] is '2'";
print "not " if $matches[4] ne 'baz'; say "ok # \$matches[4] is 'baz'";
print "not " if $matches[5] ne '3'; say "ok # \$matches[5] is '3'";

# Match with alternation in list context
$string = "apple orange banana";
$pattern = qr/(apple|orange|banana)/;
@matches = $string =~ /$pattern/g;
print "not " if scalar(@matches) != 3; say "ok # 'apple orange banana' matches '(apple|orange|banana)' 3 times in list context";
print "not " if $matches[0] ne 'apple'; say "ok # \$matches[0] is 'apple'";
print "not " if $matches[1] ne 'orange'; say "ok # \$matches[1] is 'orange'";
print "not " if $matches[2] ne 'banana'; say "ok # \$matches[2] is 'banana'";

# Match with quantifiers in list context
$string = "aaa bbb ccc";
$pattern = qr/(a{3}) (b{3}) (c{3})/;
@matches = $string =~ $pattern;
print "not " if scalar(@matches) != 3; say "ok # 'aaa bbb ccc' matches '(a{3}) (b{3}) (c{3})' in list context";
print "not " if $matches[0] ne 'aaa'; say "ok # \$matches[0] is 'aaa'";
print "not " if $matches[1] ne 'bbb'; say "ok # \$matches[1] is 'bbb'";
print "not " if $matches[2] ne 'ccc'; say "ok # \$matches[2] is 'ccc'";

# Match with lookahead in list context
$string = "foo123 bar456";
$pattern = qr/(foo)(?=\d+)/;
@matches = $string =~ /$pattern/g;
print "not " if scalar(@matches) != 1; say "ok # 'foo123 bar456' matches 'foo' followed by digits in list context";
print "not " if $matches[0] ne 'foo'; say "ok # \$matches[0] is 'foo'";

# Match with lookbehind in list context
$string = "123foo 456bar";
$pattern = qr/(?<=\d{2})(foo|bar)/;
@matches = $string =~ /$pattern/g;
print "not " if scalar(@matches) != 2; say "ok # '123foo 456bar' matches 'foo' or 'bar' preceded by digits in list context";
print "not " if $matches[0] ne 'foo'; say "ok # \$matches[0] is 'foo'";
print "not " if $matches[1] ne 'bar'; say "ok # \$matches[1] is 'bar'";

# Match with non-capturing group in list context
$string = "foo bar baz";
$pattern = qr/(?:foo) (bar) (baz)/;
@matches = $string =~ $pattern;
print "not " if scalar(@matches) != 2; say "ok # 'foo bar baz' matches '(?:foo) (bar) (baz)' in list context";
print "not " if $matches[0] ne 'bar'; say "ok # \$matches[0] is 'bar'";
print "not " if $matches[1] ne 'baz'; say "ok # \$matches[1] is 'baz'";

# Match with case insensitive flag in list context
$string = "Hello World";
$pattern = qr/(hello)/i;
@matches = $string =~ $pattern;
print "not " if scalar(@matches) != 1; say "ok # 'Hello World' matches '(hello)' case insensitively in list context";
print "not " if $matches[0] ne 'Hello'; say "ok # \$matches[0] is 'Hello'";


###################
# Perl !~ Operator Tests in Scalar Context

my ($captured1, $captured2);

# Simple non-match in scalar context
$string = "Hello World";
$pattern = qr/Goodbye/;
$match = $string !~ $pattern;
print "not " if !$match; say "ok # 'Hello World' does not match 'Goodbye'";

# Case-insensitive non-match
$string = "Hello World";
$pattern = qr/HELLO/i;
$match = $string !~ $pattern;
print "not " if $match; say "ok # 'Hello World' matches 'HELLO' with /i modifier";

# Match with quantifiers (non-match)
$string = "aaa";
$pattern = qr/a{4}/;
$match = $string !~ $pattern;
print "not " if !$match; say "ok # 'aaa' does not match 'a{4}'";

###################
# Perl !~ Operator Tests in List Context

# Simple non-match in list context (no captures)
$string = "Hello World";
$pattern = qr/(Goodbye) (World)/;
@matches = $string !~ $pattern;
print "not " if scalar(@matches) != 1; say "ok # 'Hello World' does not match '(Goodbye) (World)' in list context <@matches>";

# Non-match with captures
$string = "Hello World";
$pattern = qr/(Goodbye) (Everyone)/;
$match = $string !~ $pattern;
print "not " if !$match; say "ok # 'Hello World' does not match '(Goodbye) (Everyone)' and returns 1 in scalar context";

# Non-match with global modifier (no match)
$string = "aaa bbb ccc";
$pattern = qr/ddd/;
@matches = $string !~ /$pattern/g;
print "not " if scalar(@matches) != 1; say "ok # 'aaa bbb ccc' does not match 'ddd' globally <@matches>";

###################
# Perl !~ Operator Tests for Partial Matches

# Partial match where $1 matches but $2 does not
$string = "Hello World";
$pattern = qr/(Hello) (Universe)/;
$match = $string !~ $pattern;
print "not " if !$match; say "ok # 'Hello World' does not match '(Hello) (Universe)', so !~ should return true";

# Ensure that $1 and $2 are both undefined after the non-match
$captured1 = $1;
$captured2 = $2;
print "not " if defined $captured1; say "ok # Capture $1 should be undefined after a partial match";
print "not " if defined $captured2; say "ok # Capture $2 should be undefined after a partial match";

# Test with no captures at all
$string = "Goodbye";
$pattern = qr/(Hello) (World)/;
$match = $string !~ $pattern;
print "not " if !$match; say "ok # 'Goodbye' does not match '(Hello) (World)', so !~ should return true";
print "not " if defined $1; say "ok # Capture $1 should be undefined after a complete non-match";
print "not " if defined $2; say "ok # Capture $2 should be undefined after a complete non-match";

# Test with $1 matching but $2 not existing in the string
$string = "Hello";
$pattern = qr/(Hello)( World)?/; # The second group is optional
$match = $string !~ $pattern;
print "not " if $match; say "ok # 'Hello' matches '(Hello) (World)?', so !~ should return false";
print "not " if $1 ne 'Hello'; say "ok # Capture \$1 should be 'Hello' since it matched";
print "not " if defined $2; say "ok # Capture \$2 should be undefined as it didn't match";


