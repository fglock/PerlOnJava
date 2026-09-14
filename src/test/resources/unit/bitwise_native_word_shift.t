use strict;
use warnings;
use Test::More tests => 4;

my $high_word = 0x8000_0000;
is(($high_word << 1) >> 1, $high_word,
   'positive native word shifts retain the unsigned low word');

is(($high_word >> 31), 1,
   'positive native word right shift uses logical unsigned semantics');

is(((0xffff_ffff << 1) & 0xffff_ffff), 0xffff_fffe,
   'positive native word left shift remains maskable without wide promotion');

is((3 << -1), 1,
   'negative native shift count reverses direction');
