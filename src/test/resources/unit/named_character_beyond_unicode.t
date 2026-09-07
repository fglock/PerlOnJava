use strict;
use warnings;
use Test::More;

my $wide = "\N{U+11FFFF}";
is(length($wide), 1, 'beyond-Unicode named character is one logical character');
is(ord($wide), 0x11FFFF, 'beyond-Unicode named character retains its ordinal');
is($wide, chr(0x11FFFF), 'named character shares chr representation');

is(ord("\N{U+10FFFF}"), 0x10FFFF,
    'valid supplementary named character remains unchanged');

my $unknown = eval q{"\N{NOT A CHARACTER NAME}"};
ok(!defined $unknown, 'ordinary unknown character name remains rejected');
like($@, qr/Unknown charname 'NOT A CHARACTER NAME'/,
    'ordinary unknown character name keeps its diagnostic');

done_testing();
