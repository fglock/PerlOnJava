use strict;
use warnings;
use 5.34.0;
use Scalar::Util qw(looks_like_number);

my @tests = (
    # Positive cases
    [42, 1, "integer"],
    ["42", 1, "string integer"],
    [3.14, 1, "float"],
    ["3.14", 1, "string float"],
    ["-17", 1, "negative string integer"],
    ["-3.14", 1, "negative string float"],
    ["0", 1, "zero"],
    ["0.0", 1, "zero float"],
    ["1e10", 1, "scientific notation"],
    ["0x2A", 1, "hexadecimal"],
    ["052", 1, "octal"],
    [".5", 1, "leading decimal point"],
    ["2.", 1, "trailing decimal point"],

    # Negative cases
    ["", 0, "empty string"],
    ["abc", 0, "alphabetic string"],
    ["12abc", 0, "alphanumeric string"],
    ["0x", 0, "incomplete hex"],
    ["e10", 0, "invalid scientific notation"],
    [" ", 0, "space"],
    ["\t\n", 0, "whitespace"],
    ["Inf", 0, "infinity"],
    ["NaN", 0, "not a number"],
    [undef, 0, "undefined"],
);

my $test_num = 1;
for my $test (@tests) {
    my ($input, $expected, $description) = @$test;
    my $result = looks_like_number($input) ? 1 : 0;
    print "not " if $result != $expected;
    say "ok $test_num # looks_like_number for $description";
    $test_num++;
}

say "1.." . scalar(@tests);

