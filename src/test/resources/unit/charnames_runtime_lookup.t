use strict;
use warnings;
use Test::More tests => 6;
use charnames ();

is(charnames::vianame('LATIN CAPITAL LETTER O'), 0x4f, 'looks up a BMP character name');
is(charnames::string_vianame('LATIN CAPITAL LETTER O'), 'O', 'returns the BMP character string');
is(charnames::viacode(0xd8), 'LATIN CAPITAL LETTER O WITH STROKE', 'reverse lookup remains available');

is(charnames::vianame('GOTHIC LETTER AHSA'), 0x10330, 'looks up a supplementary character name');
is(charnames::string_vianame('GOTHIC LETTER AHSA'), "\x{10330}",
    'returns a supplementary character string');
ok(!defined charnames::vianame('NOT A REAL UNICODE CHARACTER NAME'),
    'unknown names return undef');
