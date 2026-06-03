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

subtest 'bracketed \Q...\E applies quotemeta' => sub {
    my @warnings;
    local $SIG{__WARN__} = sub { push @warnings, @_ };

    my $re = qr/[\Qabc\E]/;
    ok("a" =~ $re, '\Q...\E contents match in a character class');
    ok("b" =~ $re, 'middle contents match');
    ok("c" =~ $re, 'last contents match');
    ok("Q" !~ $re, '\Q marker is not a literal Q');
    ok("E" !~ $re, '\E marker is not a literal E');
    ok("d" !~ $re, 'characters outside the class do not match');

    my $bracket = qr/[a\Q]\E]c/;
    ok("ac" =~ $bracket, 'plain character still matches');
    ok("]c" =~ $bracket, 'quoted closing bracket remains inside the class');
    is(join("", @warnings), "", 'no warnings are emitted');
};

subtest 'interpolated bracketed \Q and \E are literal with warnings' => sub {
    my @warnings;
    local $SIG{__WARN__} = sub { push @warnings, @_ };

    my $chars = '\Qabc\E';
    my $re = qr/[$chars]/;
    ok("Q" =~ $re, 'interpolated \Q is passed through as literal Q');
    ok("E" =~ $re, 'interpolated \E is passed through as literal E');
    ok("a" =~ $re, 'interpolated character class contents still match');
    ok("d" !~ $re, 'characters outside the interpolated class do not match');
    is(join("", @warnings), '', 'interpolated \Q and \E are accepted without warnings');
    pass 'interpolated \Q and \E warning slot accounted for';
};

done_testing();
