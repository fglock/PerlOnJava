use strict;
use warnings;
use utf8;
use Test::More;

my $roman_one = "\N{SMALL ROMAN NUMERAL ONE}";
like($roman_one, qr/\p{Uppercase}/i,
    'Uppercase property closes over the titlecase Roman numeral');
like($roman_one, qr/\p{Titlecase}/i,
    'Titlecase property closes over the lowercase Roman numeral');
like("\N{ROMAN NUMERAL ONE}", qr/\p{Titlecase}/i,
    'Titlecase property closes over the uppercase Roman numeral');
like($roman_one, qr/[\p{Uppercase}]/i,
    'bracketed Uppercase property keeps its ignore-case closure');
unlike($roman_one, qr/[^\p{Uppercase}]/i,
    'inverted bracketed Uppercase property excludes its folded member');

like('A', qr/\p{PosixLower}/i,
    'POSIX lower property closes over ASCII uppercase');
like('a', qr/\p{PosixUpper}/i,
    'POSIX upper property closes over ASCII lowercase');
like("\x{00C0}", qr/\p{XPosixLower}/i,
    'Unicode POSIX lower property closes over Latin-1 uppercase');
like("\x{00E0}", qr/\p{XPosixUpper}/i,
    'Unicode POSIX upper property closes over Latin-1 lowercase');

like("\x{00C0}", qr/\p{TitlecaseLetter}/i,
    'titlecase-letter category closes over Latin-1 uppercase');
like("\x{00E0}", qr/\p{TitlecaseLetter}/i,
    'titlecase-letter category closes over Latin-1 lowercase');
unlike("\x{00C0}", qr/\p{TitlecaseLetter}/,
    'non-ignore-case titlecase-letter remains Lt-only');
like("\x{01C5}", qr/\p{TitlecaseLetter}/,
    'non-ignore-case titlecase-letter retains an actual Lt member');

unlike('K', qr/\N{KELVIN SIGN}/iaa,
    'aa still rejects ASCII-crossing class folds');
like("\N{LATIN CAPITAL LETTER SHARP S}",
    qr/[\N{LATIN SMALL LETTER SHARP S}]/iaa,
    'aa keeps wholly non-ASCII class siblings');

unlike("\N{LATIN CAPITAL LETTER SHARP S}",
    qr/\p{Block=Latin_1_Supplement}/i,
    'Block membership does not acquire sharp-s case-fold siblings');
unlike("\N{LATIN CAPITAL LETTER SHARP S}", qr/\p{Age=1.1}/i,
    'Age membership does not acquire sharp-s case-fold siblings');

done_testing;
