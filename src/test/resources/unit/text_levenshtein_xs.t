use strict;
use warnings;
use utf8;
use Test::More tests => 6;

use Text::Levenshtein::XS qw(distance);

is(distance('kitten', 'sitting'), 3, 'ordinary edit distance');
is(distance('same', 'same', 1), 0, 'threshold preserves a distance within the limit');
is(distance('kitten', 'sitting', 2), undef, 'threshold returns undef when exceeded');
is(distance("\x{24D5}\x{24DE}\x{24E4}\x{24E1}", "\x{24D5}\x{24E4}\x{24DE}\x{24E1}"), 2,
    'Unicode characters use code-point distance');
is(distance("A\x{1F600}B", "A\x{1F603}B"), 1,
    'supplementary Unicode characters count as one code point');
is($Text::Levenshtein::XS::VERSION, 0.503, 'upstream version is preserved');
