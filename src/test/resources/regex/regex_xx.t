#!/usr/bin/perl
use strict;
use warnings;
use feature 'say';

# Example of /x modifier (eXtended mode) - ignores whitespace and comments
my $pattern_x = qr/
    \d+    # match one or more digits
    -      # literal hyphen
    \d+    # match one or more digits
/x;

my $string_x1 = "123-456";
my $string_x2 = "123 - 456";

print "not " if $string_x1 !~ $pattern_x;
say "ok # Basic hyphenated number matches with /x";
print "not " if $string_x2 =~ $pattern_x;
say "ok # Spaced number does not match without further modification";

# Example of /xx modifier (eXtra eXtended mode) - more permissive whitespace handling
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

print "not " if $string_xx1 !~ $pattern_xx;
say "ok # Basic hyphenated number matches with /xx";
print "not " if $string_xx2 !~ $pattern_xx;
say "ok # Spaced number now matches with /xx";
print "not " if $string_xx3 !~ $pattern_xx;
say "ok # Widely spaced number also matches with /xx";

# Demonstrating how /x and /xx help with complex regex readability
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

print "not " if $email1 !~ $complex_pattern;
say "ok # Standard email matches with /xx";
print "not " if $1 ne "john";
say "ok # Captures username correctly despite spaces";
print "not " if $2 ne "example.com";
say "ok # Captures domain correctly despite spaces";

# Character class without /xx - hard to read
my $pattern_compressed = qr/[d-eg-i3-7]/;

# Character class with /xx - more readable
my $pattern_readable = qr/ [d-e  g-i  3-7] /xx;

# Test cases
my @test_strings = qw(d e g i 3 4 5 6 7 a b c j 8 9);

for my $str (@test_strings) {
    my $compressed_match = $str =~ $pattern_compressed;
    my $readable_match   = $str =~ $pattern_readable;

    # print "Testing '$str': ";
    if ( $compressed_match == $readable_match ) {
        say "ok # Compressed and readable patterns match consistently";
    }
    else {
        say "not ok # Patterns behave differently unexpectedly";
    }
}

# Another example with special characters
my $special_chars_compressed = qr/[!@"#$%^&*()=?<>']/;
my $special_chars_readable   = qr/ [ ! @ " # $ % ^ & * () = ? <> ' ] /xx;

my @special_test = ( qw(! @ ), '#', qw( ^ & * ( ) = ? < > ') );   ## '$', '%' );

for my $char (@special_test) {
    my $compressed_match = $char =~ $special_chars_compressed;
    my $readable_match   = $char =~ $special_chars_readable;

  # print "Testing special char '$char': '$compressed_match' '$readable_match'";
    if ( $compressed_match == $readable_match ) {
        say
"ok # Compressed and readable special char patterns match consistently";
    }
    else {
        say "not ok # Special char patterns behave differently unexpectedly: $char";
    }
}

# Demonstrating that spaces are NOT added to the character class
my $space_test_pattern = qr/ [ a b c ] /xx;
my @space_test_strings = qw(a b c);

for my $str (@space_test_strings) {

    # print "Testing space behavior with '$str': ";
    if ( $str =~ $space_test_pattern ) {
        say "ok # Match for '$str' as expected";
    }
    else {
        say "not ok # Unexpected match result";
    }
}
if ( " " =~ $space_test_pattern ) {
    say "not ok # Unexpected match result";
}
else {
    say "ok # Match for space as expected";
}

