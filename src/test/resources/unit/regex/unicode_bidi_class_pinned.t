use strict;
use warnings;
use Test::More;

my $bidi_class_probe = eval q{qr/\p{bc=L}/};
if (!defined $bidi_class_probe) {
    plan skip_all => 'pinned Bidi_Class data awaits resolver integration';
}

my @values = (
    ['AL',  'Arabic_Letter',             0x0608],
    ['AN',  'Arabic_Number',             0x0600],
    ['B',   'Paragraph_Separator',       0x000A],
    ['BN',  'Boundary_Neutral',          0x0000],
    ['CS',  'Common_Separator',          0x002C],
    ['EN',  'European_Number',           0x0030],
    ['ES',  'European_Separator',        0x002B],
    ['ET',  'European_Terminator',       0x0023],
    ['FSI', 'First_Strong_Isolate',      0x2068],
    ['L',   'Left_To_Right',             0x0041],
    ['LRE', 'Left_To_Right_Embedding',   0x202A],
    ['LRI', 'Left_To_Right_Isolate',     0x2066],
    ['LRO', 'Left_To_Right_Override',    0x202D],
    ['NSM', 'Nonspacing_Mark',           0x0300],
    ['ON',  'Other_Neutral',             0x0021],
    ['PDF', 'Pop_Directional_Format',    0x202C],
    ['PDI', 'Pop_Directional_Isolate',   0x2069],
    ['R',   'Right_To_Left',             0x05BE],
    ['RLE', 'Right_To_Left_Embedding',   0x202B],
    ['RLI', 'Right_To_Left_Isolate',     0x2067],
    ['RLO', 'Right_To_Left_Override',    0x202E],
    ['S',   'Segment_Separator',         0x0009],
    ['WS',  'White_Space',               0x000C],
);

for my $value (@values) {
    my ($short, $long, $code_point) = @$value;
    my $character = chr($code_point);
    my $short_pattern = eval "qr/\\p{bc=$short}/";
    ok(defined $short_pattern, "short value $short compiles")
        or diag($@);
    like($character, $short_pattern, "short value $short matches representative");

    my $long_pattern = eval "qr/\\p{Bidi_Class=$long}/";
    ok(defined $long_pattern, "long value $long compiles")
        or diag($@);
    like($character, $long_pattern, "long value $long matches representative");
}

my $loose = eval q{qr/\p{bidi class = arabic letter}/};
ok(defined $loose, 'loose property and value aliases compile') or diag($@);
like(chr(0x0608), $loose, 'loose property and value aliases match');

my @defaults = (
    [0x0590, 'R',  'Hebrew missing default is Right_To_Left'],
    [0x088B, 'AL', 'Arabic missing default is Arabic_Letter'],
    [0x20CF, 'ET', 'currency missing default is European_Terminator'],
    [0x0378, 'L',  'general missing default is Left_To_Right'],
    [0x10FFFF, 'BN', 'noncharacter explicit value overrides defaults'],
);
for my $default (@defaults) {
    my ($code_point, $value, $description) = @$default;
    my $pattern = eval "qr/\\p{bc=$value}/";
    like(chr($code_point), $pattern, $description);
}

done_testing();
