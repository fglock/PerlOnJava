use strict;
use warnings;
use utf8;
use Test::More;

my @cases = (
    [q{((x)}, q{Unmatched ( in regex; marked by <-- HERE in m/( <-- HERE (x)/}],
    [q|{(}|, q|Unmatched ( in regex; marked by <-- HERE in m/{( <-- HERE }/|],
    [q{ネ((ネ)}, q{Unmatched ( in regex; marked by <-- HERE in m/ネ( <-- HERE (ネ)/}],
);

my $case_number = 0;
for my $case (@cases) {
    $case_number++;
    my ($pattern, $expected) = @$case;
    eval "#line 1 regex_unmatched_open_paren_diagnostics.t\nqr/$pattern/";
    my ($error) = split /\n/, $@;
    like($error, qr/^\Q$expected\E at /, "unmatched opening parenthesis case $case_number");
}

done_testing;
