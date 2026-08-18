use strict;
use warnings;
use Test::More;

my $east_asian_width_probe = eval q{qr/\p{ea=W}/};
if (!defined $east_asian_width_probe) {
    plan skip_all => 'pinned East_Asian_Width data awaits resolver integration';
}

my @values = (
    ['A',  'Ambiguous',  0x00A1],
    ['F',  'Fullwidth',  0xFF01],
    ['H',  'Halfwidth',  0xFF61],
    ['N',  'Neutral',    0x0000],
    ['Na', 'Narrow',     0x0020],
    ['W',  'Wide',       0x1100],
);

for my $value (@values) {
    my ($short, $long, $code_point) = @$value;
    my $character = chr($code_point);
    my $short_pattern = eval "qr/\\p{ea=$short}/";
    ok(defined $short_pattern, "short value $short compiles")
        or diag($@);
    like($character, $short_pattern, "short value $short matches representative");

    my $long_pattern = eval "qr/\\p{East_Asian_Width=$long}/";
    ok(defined $long_pattern, "long value $long compiles")
        or diag($@);
    like($character, $long_pattern, "long value $long matches representative");
}

my $loose = eval q{qr/\p{east asian width = ambiguous}/};
ok(defined $loose, 'loose property and value aliases compile') or diag($@);
like(chr(0x00A1), $loose, 'loose property and value aliases match');

my @defaults = (
    [0x3FF0,  'W', 'Extension A missing default is Wide'],
    [0x2FA20, 'W', 'supplementary CJK missing default is Wide'],
    [0x3FFFD, 'W', 'plane 3 CJK missing default is Wide'],
    [0x0378,  'N', 'general missing default is Neutral'],
    [0x10FFFF, 'N', 'final code point uses the general Neutral default'],
);
for my $default (@defaults) {
    my ($code_point, $value, $description) = @$default;
    my $pattern = eval "qr/\\p{ea=$value}/";
    like(chr($code_point), $pattern, $description);
}

done_testing();
