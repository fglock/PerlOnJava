use strict;
use warnings;
use utf8;
use Test::More;

ok("\x{2800}" =~ /\p{inbraille}/,
    'Perl short InBraille spelling resolves the Braille Patterns block');
ok("\x{27FF}" !~ /\p{inbraille}/,
    'InBraille keeps the block boundary');
ok("\x{2000}" =~ /\p{inpunctuation}/,
    'Perl InPunctuation spelling resolves General Punctuation');
ok("\x{FE00}" =~ /\p{invs}/,
    'Perl InVS spelling resolves Variation Selectors');
ok("\x{E000}" =~ /\p{inprivateuse}/,
    'Perl InPrivateUse spelling resolves the BMP Private Use Area');
ok("\x{F0000}" !~ /\p{inprivateuse}/,
    'InPrivateUse does not broaden to supplementary private-use blocks');
ok("\x{2FF0}" =~ /\p{inidc}/,
    'Perl InIDC spelling resolves Ideographic Description Characters');
ok("\x{2800}" =~ /\p{ _-In_Braille}/,
    'In Block shortcuts retain Perl loose ASCII separators');

ok("\x{0370}" =~ /\p{InGreek}/,
    'InGreek includes Greek and Coptic');
ok("\x{1F00}" !~ /\p{InGreek}/,
    'InGreek remains a block shortcut rather than Script Greek');
ok("\x{0600}" =~ /\p{InArabic}/,
    'InArabic includes the Arabic block');
ok("\x{0750}" !~ /\p{InArabic}/,
    'InArabic remains a block shortcut rather than Script Arabic');
ok("\x{30A0}" =~ /\p{InKatakana}/,
    'InKatakana includes the Katakana block');
ok("\x{31F0}" !~ /\p{InKatakana}/,
    'InKatakana remains a block shortcut rather than Script Katakana');

done_testing;
