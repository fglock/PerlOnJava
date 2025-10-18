#!/usr/bin/perl
use strict;
use warnings;
use Test::More;

plan tests => 40;

# Example of /x modifier (eXtended mode)
my $pattern_x = qr/
    \d+    # match one or more digits
    -      # literal hyphen
    \d+    # match one or more digits
/x;

my $string_x1 = "123-456";
my $string_x2 = "123 - 456";

ok($string_x1 =~ $pattern_x, "Basic hyphenated number matches with /x");
ok($string_x2 !~ $pattern_x, "Spaced number does not match without further modification");

# Example of /xx modifier
my $pattern_xx = qr/
    \d+    # match one or more digits
    \s*    # optional whitespace
    -      # literal hyphen
    \s*    # optional whitespace
    \d+    # match one or more digits
/xx;

my $string_xx1 = "123-456";
my $string_xx2 = "123 - 456";
my $string_xx3 = "123  -   456";

ok($string_xx1 =~ $pattern_xx, "Basic hyphenated number matches with /xx");
ok($string_xx2 =~ $pattern_xx, "Spaced number matches with /xx");
ok($string_xx3 =~ $pattern_xx, "Widely spaced number matches with /xx");

# Complex regex readability test
my $complex_pattern = qr/
    ^           # start of string
    \s*         # optional leading whitespace
    (           # capture group for username
        [a-z]   # must start with a lowercase letter
        \w*     # followed by word characters
    )
    \s*         # optional whitespace
    @           # literal @ symbol
    \s*         # optional whitespace
    (           # capture group for domain
        [a-z]+  # domain name (lowercase letters)
        \.      # literal dot
        [a-z]{2,4} # top-level domain
    )
    $           # end of string
/xx;

my $email1 = "john\@example.com";

ok($email1 =~ $complex_pattern, "Standard email matches with /xx");
is($1, "john", "Captures username correctly despite spaces");
is($2, "example.com", "Captures domain correctly despite spaces");

# Character class tests
my $pattern_compressed = qr/[d-eg-i3-7]/;
my $pattern_readable = qr/ [d-e  g-i  3-7] /xx;

my @test_strings = qw(d e g i 3 4 5 6 7 a b c j 8 9);
for my $str (@test_strings) {
    my $compressed_match = $str =~ $pattern_compressed;
    my $readable_match = $str =~ $pattern_readable;
    is($compressed_match, $readable_match, "Pattern consistency for char: $str");
}

# Special characters test
my $special_chars_compressed = qr/[!@"#$%^&*()=?<>']/;
my $special_chars_readable = qr/ [ ! @ " # $ % ^ & * () = ? <> ' ] /xx;

my @special_test = (qw(! @ ), '#', qw( ^ & * ( ) = ? < > '));
for my $char (@special_test) {
    my $compressed_match = $char =~ $special_chars_compressed;
    my $readable_match = $char =~ $special_chars_readable;
    is($compressed_match, $readable_match, "Special char pattern consistency: $char");
}

# Space behavior test
my $space_test_pattern = qr/ [ a b c ] /xx;
my @space_test_strings = qw(a b c);

for my $str (@space_test_strings) {
    ok($str =~ $space_test_pattern, "Match for '$str' as expected");
}
ok(" " !~ $space_test_pattern, "Space correctly does not match");

done_testing();
