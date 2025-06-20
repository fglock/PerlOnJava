use strict;
use feature 'say';
use Test::More;

###################
# Perl m// Operator Tests

subtest "Simple match tests" => sub {
    my $string = "Hello World";
    my $pattern = qr/World/;
    my $match = $string =~ $pattern;
    ok($match, "'Hello World' matches 'World'");

    my $ref;
    # $ref = "" . $pattern;
    # is($ref, "(?^:World)", "ref <$ref>");
    $ref = ref($pattern);
    is($ref, "Regexp", "ref <$ref>");
    $ref = ref(\$pattern);
    is($ref, "REF", "ref <$ref>");

    # Test special variables $`, $&, $'
    is($`, 'Hello ', "\$` is 'Hello ' <$`>");
    is($&, 'World', "\$& is 'World' <$&>");
    is($', '', "\$' is '' <$'>");

    # No match
    $pattern = qr/Universe/;
    $match = $string =~ $pattern;
    ok(!$match, "'Hello World' does not match 'Universe'");
};

subtest "Match with capture groups" => sub {
    my $string = "Hello World";
    my $pattern = qr/(Hello) (World)/;
    my $match = $string =~ $pattern;
    ok($match, "'Hello World' matches '(Hello) (World)'");
    is($1, 'Hello', "\$1 is 'Hello' <$1>");
    is($2, 'World', "\$2 is 'World' <$2>");

    # Test special variables $`, $&, $'
    is($`, '', "\$` is '' <$`>");
    is($&, 'Hello World', "\$& is 'Hello World' <$&>");
    is($', '', "\$' is '' <$'>");

    # Match with multiple capture groups
    $string = "foo bar baz";
    $pattern = qr/(foo) (bar) (baz)/;
    $match = $string =~ $pattern;
    ok($match, "'foo bar baz' matches '(foo) (bar) (baz)'");
    is($1, 'foo', "\$1 is 'foo'");
    is($2, 'bar', "\$2 is 'bar'");
    is($3, 'baz', "\$3 is 'baz'");

    # Test special variables $`, $&, $'
    is($`, '', "\$` is '' <$`>");
    is($&, 'foo bar baz', "\$& is 'foo bar baz' <$&>");
    is($', '', "\$' is '' <$'>");
};

subtest "Match with flags and special patterns" => sub {
    # Match with global flag
    my $string = "abc abc abc";
    my $pattern = qr/abc/;
    my @matches = $string =~ /$pattern/g;
    is(scalar(@matches), 3, "'abc abc abc' matches 'abc' 3 times <@matches>");

    # Match with case insensitive flag
    $string = "Hello World";
    $pattern = qr/hello/i;
    my $match = $string =~ $pattern;
    ok($match, "'Hello World' matches 'hello' case insensitively");

    # Match with non-capturing group
    $string = "foo bar baz";
    $pattern = qr/(?:foo) (bar) (baz)/;
    $match = $string =~ $pattern;
    ok($match, "'foo bar baz' matches '(?:foo) (bar) (baz)'");
    is($1, 'bar', "\$1 is 'bar'");
    is($2, 'baz', "\$2 is 'baz'");

    # Match with lookahead
    $string = "foo123";
    $pattern = qr/foo(?=\d+)/;
    $match = $string =~ $pattern;
    ok($match, "'foo123' matches 'foo' followed by digits");

    # Match with lookbehind
    $string = "123foo";
    $pattern = qr/(?<=\d{2})foo/;
    $match = $string =~ $pattern;
    ok($match, "'123foo' matches 'foo' preceded by digits");

    # Match with alternation
    $string = "apple";
    $pattern = qr/apple|orange/;
    $match = $string =~ $pattern;
    ok($match, "'apple' matches 'apple' or 'orange'");

    # Match with quantifiers
    $string = "aaa";
    $pattern = qr/a{3}/;
    $match = $string =~ $pattern;
    ok($match, "'aaa' matches 'a{3}'");
};

###################
# Perl m// Operator Tests in List Context

subtest "Match in list context" => sub {
    # Simple match in list context
    my $string = "Hello World";
    my $pattern = qr/(Hello) (World)/;
    my @matches = $string =~ $pattern;
    is(scalar(@matches), 2, "'Hello World' matches '(Hello) (World)' in list context <" . scalar(@matches) . ">");
    is($matches[0], 'Hello', "\$matches[0] is 'Hello'");
    is($matches[1], 'World', "\$matches[1] is 'World'");

    # Multiple matches in list context
    $string = "foo bar baz";
    $pattern = qr/(foo) (bar) (baz)/;
    @matches = $string =~ $pattern;
    is(scalar(@matches), 3, "'foo bar baz' matches '(foo) (bar) (baz)' in list context");
    is($matches[0], 'foo', "\$matches[0] is 'foo'");
    is($matches[1], 'bar', "\$matches[1] is 'bar'");
    is($matches[2], 'baz', "\$matches[2] is 'baz'");

    # Global match in list context
    $string = "abc abc abc";
    $pattern = qr/(abc)/;
    @matches = $string =~ /$pattern/g;
    is(scalar(@matches), 3, "'abc abc abc' matches 'abc' 3 times in list context");
    is($matches[0], 'abc', "\$matches[0] is 'abc'");
    is($matches[1], 'abc', "\$matches[1] is 'abc'");
    is($matches[2], 'abc', "\$matches[2] is 'abc'");

    # Match with capture groups and global flag in list context
    $string = "foo1 bar2 baz3";
    $pattern = qr/(\w+)(\d)/;
    @matches = $string =~ /$pattern/g;
    is(scalar(@matches), 6, "'foo1 bar2 baz3' matches '(\\w+)(\\d)' 3 times in list context");
    is($matches[0], 'foo', "\$matches[0] is 'foo'");
    is($matches[1], '1', "\$matches[1] is '1'");
    is($matches[2], 'bar', "\$matches[2] is 'bar'");
    is($matches[3], '2', "\$matches[3] is '2'");
    is($matches[4], 'baz', "\$matches[4] is 'baz'");
    is($matches[5], '3', "\$matches[5] is '3'");

    # Match with alternation in list context
    $string = "apple orange banana";
    $pattern = qr/(apple|orange|banana)/;
    @matches = $string =~ /$pattern/g;
    is(scalar(@matches), 3, "'apple orange banana' matches '(apple|orange|banana)' 3 times in list context");
    is($matches[0], 'apple', "\$matches[0] is 'apple'");
    is($matches[1], 'orange', "\$matches[1] is 'orange'");
    is($matches[2], 'banana', "\$matches[2] is 'banana'");

    # Match with quantifiers in list context
    $string = "aaa bbb ccc";
    $pattern = qr/(a{3}) (b{3}) (c{3})/;
    @matches = $string =~ $pattern;
    is(scalar(@matches), 3, "'aaa bbb ccc' matches '(a{3}) (b{3}) (c{3})' in list context");
    is($matches[0], 'aaa', "\$matches[0] is 'aaa'");
    is($matches[1], 'bbb', "\$matches[1] is 'bbb'");
    is($matches[2], 'ccc', "\$matches[2] is 'ccc'");

    # Match with lookahead in list context
    $string = "foo123 bar456";
    $pattern = qr/(foo)(?=\d+)/;
    @matches = $string =~ /$pattern/g;
    is(scalar(@matches), 1, "'foo123 bar456' matches 'foo' followed by digits in list context");
    is($matches[0], 'foo', "\$matches[0] is 'foo'");

    # Match with lookbehind in list context
    $string = "123foo 456bar";
    $pattern = qr/(?<=\d{2})(foo|bar)/;
    @matches = $string =~ /$pattern/g;
    is(scalar(@matches), 2, "'123foo 456bar' matches 'foo' or 'bar' preceded by digits in list context");
    is($matches[0], 'foo', "\$matches[0] is 'foo'");
    is($matches[1], 'bar', "\$matches[1] is 'bar'");

    # Match with non-capturing group in list context
    $string = "foo bar baz";
    $pattern = qr/(?:foo) (bar) (baz)/;
    @matches = $string =~ $pattern;
    is(scalar(@matches), 2, "'foo bar baz' matches '(?:foo) (bar) (baz)' in list context");
    is($matches[0], 'bar', "\$matches[0] is 'bar'");
    is($matches[1], 'baz', "\$matches[1] is 'baz'");

    # Match with case insensitive flag in list context
    $string = "Hello World";
    $pattern = qr/(hello)/i;
    @matches = $string =~ $pattern;
    is(scalar(@matches), 1, "'Hello World' matches '(hello)' case insensitively in list context");
    is($matches[0], 'Hello', "\$matches[0] is 'Hello'");
};

###################
# Perl !~ Operator Tests in Scalar Context

subtest "!~ operator in scalar context" => sub {
    my ($string, $pattern, $match);
    my ($captured1, $captured2);

    # Simple non-match in scalar context
    $string = "Hello World";
    $pattern = qr/Goodbye/;
    $match = $string !~ $pattern;
    ok($match, "'Hello World' does not match 'Goodbye'");

    # Case-insensitive non-match
    $string = "Hello World";
    $pattern = qr/HELLO/i;
    $match = $string !~ $pattern;
    ok(!$match, "'Hello World' matches 'HELLO' with /i modifier");

    # Match with quantifiers (non-match)
    $string = "aaa";
    $pattern = qr/a{4}/;
    $match = $string !~ $pattern;
    ok($match, "'aaa' does not match 'a{4}'");
};

###################
# Perl !~ Operator Tests in List Context

subtest "!~ operator in list context" => sub {
    my ($string, $pattern, @matches, $match);

    # Simple non-match in list context (no captures)
    $string = "Hello World";
    $pattern = qr/(Goodbye) (World)/;
    @matches = $string !~ $pattern;
    is(scalar(@matches), 1, "'Hello World' does not match '(Goodbye) (World)' in list context <@matches>");

    # Non-match with captures
    $string = "Hello World";
    $pattern = qr/(Goodbye) (Everyone)/;
    $match = $string !~ $pattern;
    ok($match, "'Hello World' does not match '(Goodbye) (Everyone)' and returns 1 in scalar context");

    # Non-match with global modifier (no match)
    $string = "aaa bbb ccc";
    $pattern = qr/ddd/;
    @matches = $string !~ /$pattern/g;
    is(scalar(@matches), 1, "'aaa bbb ccc' does not match 'ddd' globally <@matches>");
};

###################
# Perl !~ Operator Tests for Partial Matches

subtest "!~ operator with partial matches" => sub {
    my ($string, $pattern, $match);
    my ($captured1, $captured2);

    # Partial match where $1 matches but $2 does not
    $string = "Hello World";
    $pattern = qr/(Hello) (Universe)/;
    $match = $string !~ $pattern;
    ok($match, "'Hello World' does not match '(Hello) (Universe)', so !~ should return true");

    # Ensure that $1 and $2 are both undefined after the non-match
    $captured1 = $1;
    $captured2 = $2;
    ok(!defined $captured1, "Capture \$1 should be undefined after a partial match");
    ok(!defined $captured2, "Capture \$2 should be undefined after a partial match");

    # Test with no captures at all
    $string = "Goodbye";
    $pattern = qr/(Hello) (World)/;
    $match = $string !~ $pattern;
    ok($match, "'Goodbye' does not match '(Hello) (World)', so !~ should return true");
    ok(!defined $1, "Capture \$1 should be undefined after a complete non-match");
    ok(!defined $2, "Capture \$2 should be undefined after a complete non-match");

    # Test with $1 matching but $2 not existing in the string
    $string = "Hello";
    $pattern = qr/(Hello)( World)?/; # The second group is optional
    $match = $string !~ $pattern;
    ok(!$match, "'Hello' matches '(Hello) (World)?', so !~ should return false");
    is($1, 'Hello', "Capture \$1 should be 'Hello' since it matched");
    ok(!defined $2, "Capture \$2 should be undefined as it didn't match");
};

done_testing();
