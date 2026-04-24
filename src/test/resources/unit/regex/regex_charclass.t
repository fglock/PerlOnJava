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

# Regression: `[\c?]` must match only U+007F (DEL), not U+001C.
# Earlier, the preprocessor escaped `?` inside [] as `\?`, so `[\c?]`
# leaked out as `[\c\?]` which Java parses as `\c\` (= 0x1C) plus a
# literal `?`, matching the wrong code point.  This silently corrupted
# patterns like `[\n\t\c?[:^cntrl:]]` used by JSON::PP's ASCII escape
# regex (and any other Perl code that uses `\c?` inside a class).
subtest 'bracketed \c? matches DEL only' => sub {
    my @matched;
    for my $i (0x00..0x1f, 0x7f) {
        push @matched, sprintf("0x%02x", $i) if chr($i) =~ /[\c?]/;
    }
    is_deeply(\@matched, ["0x7f"], '[\c?] matches U+007F only');

    # Sanity: literal `?` still works in classes and as a quantifier.
    ok("?" =~ /[?]/, "bracketed literal ? still matches");
    ok("colour" =~ /colou?r/, "? quantifier still works");
    ok("color"  =~ /colou?r/, "? quantifier still works (absent)");
};

done_testing();
