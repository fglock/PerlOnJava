use strict;
use warnings;
use charnames ':full';
use Test::More;

my $keycap = "\N{KEYCAP DIGIT NINE}";
is_deeply(
    [map { ord } split //, $keycap],
    [0x39, 0xFE0F, 0x20E3],
    'KEYCAP DIGIT NINE expands to its pinned three-code-point sequence');

my $latin = "\N{LATIN CAPITAL LETTER A WITH MACRON AND GRAVE}";
is_deeply(
    [map { ord } split //, $latin],
    [0x0100, 0x0300],
    'non-emoji named sequence expands from the pinned table');

is("\N{LATIN CAPITAL LETTER A}", 'A',
    'ordinary scalar Unicode names still resolve');

like($keycap, qr/\A\N{KEYCAP DIGIT NINE}\z/,
    'named sequence matches as a complete regex atom');
unlike("9\x{FE0F}", qr/\A\N{KEYCAP DIGIT NINE}\z/,
    'named sequence regex requires every code point');

my $class = qr/[x\N{KEYCAP DIGIT NINE}]/;
like($keycap, $class, 'named sequence matches inside a legal character class');
like('x', $class, 'ordinary class alternatives remain available');

done_testing;
