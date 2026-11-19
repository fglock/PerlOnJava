use strict;
use warnings;
use Unicode::Normalize qw(getComposite);
use Test::More;

is(getComposite(0x0061, 0x030A), 0x00E5, 'composes Latin base and combining ring');
is(getComposite(0x1100, 0x1161), 0xAC00, 'composes Hangul leading and vowel jamo');
ok(!defined getComposite(0x0061, 0x0062), 'returns undef for a non-composable pair');

done_testing;
