#!/usr/bin/perl
use strict;
use warnings;
use Test::More;

plan tests => 20;

# Test 1-4: Basic /x modifier behavior in matching
{
    my $pattern = qr/ a b c /x;  # Should match "abc" (spaces ignored)
    ok("abc" =~ $pattern, "/x modifier ignores spaces in qr// - matches 'abc'");
    ok("a b c" !~ $pattern, "/x modifier ignores spaces in qr// - doesn't match 'a b c'");

    my $pattern2 = qr/ a b c /;  # Without /x, spaces are literal
    ok("abc" !~ $pattern2, "Without /x modifier - doesn't match 'abc'");
    ok(" a b c " =~ $pattern2, "Without /x modifier - matches ' a b c '");
}

# Test 5-8: Basic substitution with /x modifier
{
    my $str = "abc123";
    my $result = $str =~ s/ 123 /456/xr;
    is($result, "abc456", "s///xr with spaces in pattern - successful substitution");

    $str = "abc123";
    $result = $str =~ s/ 1 2 3 /456/xr;
    is($result, "abc456", "s///xr with multiple spaces in pattern - successful substitution");

    $str = "abc 123";
    $result = $str =~ s/ 123 /456/xr;
    is($result, "abc 456", "s///xr without /x - literal space matters");

    $str = "test";
    $result = $str =~ s/test/pass/xr;
    is($result, "pass", "s///xr basic substitution works");
}

# Test 9-12: Non-matching patterns with /r modifier
{
    my $str = "hello";
    my $result = $str =~ s/nomatch/replace/r;
    is($result, "hello", "s///r with non-matching pattern returns original");

    $str = "hello";
    $result = $str =~ s/nomatch/replace/xr;
    is($result, "hello", "s///xr with non-matching pattern returns original");

    $str = "hello";
    $result = $str =~ s/ nomatch /replace/xr;
    is($result, "hello", "s///xr with non-matching pattern with spaces returns original");

    $str = "hello";
    $result = $str =~ s/ no match /replace/xr;
    is($result, "hello", "s///xr with non-matching pattern with internal spaces returns original");
}

# Test 13-16: Edge cases with leading/trailing spaces
{
    my $str = "test";
    my $result = $str =~ s/ test /TEST/xr;
    is($result, "TEST", "s///xr pattern with leading/trailing spaces - matches");

    $str = "test";
    $result = $str =~ s/   test   /TEST/xr;
    is($result, "TEST", "s///xr pattern with multiple leading/trailing spaces - matches");

    $str = "/[[=foo=]]/";
    $result = $str =~ s/ default_ (on | off) //xr;
    is($result, "/[[=foo=]]/", "s///xr complex non-matching pattern returns original");

    $str = "abc";
    $result = $str =~ s/ xyz //xr;
    is($result, "abc", "s///xr non-matching pattern with spaces and empty replacement");
}

# Test 17-20: Comments in /x modifier
{
    my $str = "abc123";
    my $result = $str =~ s/
        abc  # letters
        123  # numbers
    /replaced/xr;
    is($result, "replaced", "s///xr with comments - successful substitution");

    $str = "test";
    $result = $str =~ s/
        no   # this won't
        match # match anything
    /replaced/xr;
    is($result, "test", "s///xr with comments in non-matching pattern returns original");

    $str = "hello world";
    $result = $str =~ s/
        hello
        \s+     # whitespace
        world
    /HELLO WORLD/xr;
    is($result, "HELLO WORLD", "s///xr with comments and \\s+ works");

    $str = "test";
    $result = $str =~ s/# just a comment
        test/PASS/xr;
    is($result, "PASS", "s///xr with leading comment works");
}

done_testing();