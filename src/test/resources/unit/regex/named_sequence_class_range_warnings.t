use strict;
use warnings;
use charnames ':full';
use Test::More;

my @patterns = (
    q{[^\N{LATIN CAPITAL LETTER A WITH MACRON AND GRAVE}]},
    q{[\x03-\N{LATIN CAPITAL LETTER A WITH MACRON AND GRAVE}]},
    q{[\N{LATIN CAPITAL LETTER A WITH MACRON AND GRAVE}-\x{10FFFF}]},
);

for my $index (0 .. $#patterns) {
    my @warnings;
    my $regex;
    {
        local $SIG{__WARN__} = sub { push @warnings, @_ };
        $regex = eval "qr/$patterns[$index]/";
    }
    ok(defined $regex, "single-character context $index compiles");
    is(scalar @warnings, 1, "single-character context $index warns once");
    like($warnings[0],
        qr/Using just the first character returned by \\N\{\} in character class/,
        "single-character context $index uses the named-sequence warning");
    like($warnings[0], qr/\\N\{U\+100\.300\} <-- HERE /,
        "single-character context $index renders the canonical sequence");
}

done_testing;
