use strict;
use warnings;
use charnames ':full';
use Test::More tests => 21;

my $lower = "\N{LATIN SMALL LETTER A WITH GRAVE}";
my $upper = "\N{LATIN CAPITAL LETTER A WITH GRAVE}";

ok(utf8::is_utf8($lower), 'named lower is Unicode-upgraded');
ok(utf8::is_utf8($upper), 'named upper is Unicode-upgraded');
is(ord($lower), 0xE0, 'named lower retains its code point');
is(ord($upper), 0xC0, 'named upper retains its code point');

ok($lower =~ qr/$upper/i, 'interpolated named upper matches lower');
ok($upper =~ qr/$lower/i, 'interpolated named lower matches upper');
ok($lower =~ qr/[$upper]/i, 'interpolated named upper class matches lower');
ok($upper =~ qr/[$lower]/i, 'interpolated named lower class matches upper');

ok($lower =~ qr/\N{LATIN CAPITAL LETTER A WITH GRAVE}/i,
    'literal named upper matches lower');
ok($upper =~ qr/\N{LATIN SMALL LETTER A WITH GRAVE}/i,
    'literal named lower matches upper');

my $ascii = "\N{LATIN CAPITAL LETTER A}";
ok(utf8::is_utf8($ascii), 'ASCII named character is Unicode-upgraded');
is(ord($ascii), 0x41, 'ASCII named character retains its code point');

my $literal_ref = \("\N{LATIN CAPITAL LETTER A WITH GRAVE}");
ok(utf8::is_utf8($$literal_ref), 'reference to named literal stays Unicode-upgraded');
is(ord($$literal_ref), 0xC0, 'referenced named literal retains its code point');

my $sharp_s = "\N{LATIN SMALL LETTER SHARP S}";
ok($sharp_s =~ qr/ss/i, 'ASCII lower pair folds to named sharp s subject');
ok($sharp_s =~ qr/SS/i, 'ASCII upper pair folds to named sharp s subject');

ok("foba  ba$sharp_s" =~ qr/(foo|Bass|bar)/i && $1 eq "ba$sharp_s",
    'trie ASCII lower pair folds to named sharp s subject');
ok("foba  ba$sharp_s" =~ qr/(foo|BaSS|bar)/i && $1 eq "ba$sharp_s",
    'trie ASCII upper pair folds to named sharp s subject');
ok("foba  ba${sharp_s}pxySS$sharp_s$sharp_s"
        =~ qr/(b(?:a${sharp_s}t|a${sharp_s}f|a${sharp_s}p)[xy]+$sharp_s*)/i
        && $1 eq "ba${sharp_s}pxySS$sharp_s$sharp_s",
    'common-prefix trie preserves named sharp s folds');

my $mixed = "\xFF\N{LATIN CAPITAL LETTER A WITH GRAVE}";
ok(utf8::is_utf8($mixed), 'named escape upgrades an adjacent byte escape');
is(join(',', map { ord } split //, $mixed), '255,192',
    'adjacent byte and named escapes retain their code points');
