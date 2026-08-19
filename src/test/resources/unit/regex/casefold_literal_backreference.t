use strict;
use warnings;
use utf8;
use Test::More;

like('ss', qr/^\x{00DF}$/iu, 'sharp-s forward full fold');
like("\x{00DF}", qr/^ss$/iu, 'sharp-s reverse full fold');
like("\x{FB03}", qr/^ffi$/i, 'ligature reverse full fold');
like("\x{017F}\x{017F}", qr/^\x{00DF}$/i, 'long-s components fold to sharp s');
like("\x{212A}", qr/^k$/i, 'Kelvin folds with ASCII k');
unlike('I', qr/^\x{0131}$/i, 'Turkic dotless I remains excluded');

like("\x{1E9E}\x{1E9E}", qr/^(\x{00DF})\1$/i,
    'backreference consumes folded non-ASCII siblings');
unlike('ssss', qr/^(\x{00DF})\1$/iaa,
    'aa prevents ASCII-crossing backreference folds');
like("\x{1E9E}\x{1E9E}", qr/^(\x{00DF})\1$/iaa,
    'aa retains non-ASCII sibling backreference folds');

like('xssy', qr/^x(?i:\x{00DF})y$/u, 'scoped i enables fold locally');
unlike('xssy', qr/^x(?-i:\x{00DF})y$/i, 'scoped minus i restores outer policy');

my $byte_sharp = chr 0xDF;
utf8::downgrade($byte_sharp, 1);
unlike('ss', qr/^$byte_sharp$/di, 'byte d literal blocks full fold');
my $unicode_sharp = $byte_sharp;
utf8::upgrade($unicode_sharp);
like('ss', qr/^$unicode_sharp$/di, 'upgraded d literal enables full fold');

like('affi', qr/^aff\x{0069}$/i, 'positive adjacent reverse-fold boundary');
unlike('affx', qr/^aff\x{0069}$/i, 'negative adjacent reverse-fold boundary');

done_testing;
