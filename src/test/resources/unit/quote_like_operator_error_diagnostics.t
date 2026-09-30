use strict;
use warnings;
use Test::More;

for my $case (
    [ "s('unqualified')",  qr/^Substitution replacement not terminated/ ],
    [ "tr('unqualified')", qr/^Transliteration replacement not terminated/ ],
    [ "y('unqualified')",  qr/^Transliteration replacement not terminated/ ],
) {
    my ($source, $expected) = @$case;
    eval $source;
    like $@, $expected, "$source reports its quote-like operator diagnostic";
}

done_testing;
