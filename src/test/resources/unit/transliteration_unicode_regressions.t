use strict;
use warnings;
use Test::More;

my $named_sequence = eval q{tr/a/\N{KATAKANA LETTER AINU P}/; 1};
ok(!defined($named_sequence), 'tr rejects a Unicode named sequence');
like($@, qr/\N\{KATAKANA LETTER AINU P\} must not be a named sequence in transliteration operator/,
    'named sequence diagnostic identifies transliteration');

my $text = "A\x{ffff}B";
$text =~ tr/\x{ffff}/\x{1ffff}/;
is($text, "A\x{1ffff}B", 'tr preserves a supplementary replacement code point');

done_testing();
