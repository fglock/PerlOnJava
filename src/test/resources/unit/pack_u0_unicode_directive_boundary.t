use strict;
use warnings;
use Test::More tests => 4;

my $latin1 = pack 'U0U C0 W', 0xF8, 0xF9;
is length($latin1), 2,
    'U after U0 emits a code point rather than an encoded byte segment';
is_deeply [unpack 'U*', $latin1], [0xF8, 0xF9],
    'C0 preserves the direct U code point before a following W';

my $mixed = pack 'U0U C2', 0xF8, 0xC2, 0xA2;
is length($mixed), 2,
    'byte-producing directives resume UTF-8 decoding after direct U';
is_deeply [unpack 'U*', $mixed], [0xF8, 0xA2],
    'direct U and a later UTF-8 byte segment retain their order';
