use strict;
use warnings;
use Test::More;

my @cases = (
    [q{(?\ix}, q{Sequence (?\...) not recognized in regex; marked by <-- HERE in m/(?\ <-- HERE ix/}],
    [q{(?\:x}, q{Sequence (?\...) not recognized in regex; marked by <-- HERE in m/(?\ <-- HERE :x/}],
    [q{(?\<=x}, q{Sequence (?\...) not recognized in regex; marked by <-- HERE in m/(?\ <-- HERE <=x/}],
);

for my $case (@cases) {
    my ($pattern, $expected) = @$case;
    eval "#line 1 regex_escaped_group_option_diagnostics.t\nqr/$pattern/";
    my ($error) = split /\n/, $@;
    like($error, qr/^\Q$expected\E at /, "escaped group option $pattern");
}

done_testing;
