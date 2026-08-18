use strict;
use warnings;
use utf8;
use Test::More;

my @properties = (
    {
        short => 'InPC',
        long  => 'Indic_Positional_Category',
        values => [
            [Bottom => 0x093C],
            [Bottom_And_Left => 0xA9BF],
            [Bottom_And_Right => 0x1B3B],
            [Left => 0x093F],
            [Left_And_Right => 0x09CB],
            [NA => 0x0041, 'Not_Applicable'],
            [Overstruck => 0x1CD4],
            [Right => 0x0903],
            [Top => 0x0900],
            [Top_And_Bottom => 0x0C48],
            [Top_And_Bottom_And_Left => 0x103C],
            [Top_And_Bottom_And_Right => 0x1B3D],
            [Top_And_Left => 0x0B48],
            [Top_And_Left_And_Right => 0x0B4C],
            [Top_And_Right => 0x0AC9],
            [Visual_Order_Left => 0x0E40],
        ],
    },
    {
        short => 'InSC',
        long  => 'Indic_Syllabic_Category',
        values => [
            [Avagraha => 0x093D], [Bindu => 0x0900],
            [Brahmi_Joining_Number => 0x11052], [Cantillation_Mark => 0x0951],
            [Consonant => 0x0915], [Consonant_Dead => 0x09CE],
            [Consonant_Final => 0x1930], [Consonant_Head_Letter => 0x0F88],
            [Consonant_Initial_Postfixed => 0x1A5A], [Consonant_Killer => 0x0E4C],
            [Consonant_Medial => 0x0A75], [Consonant_Placeholder => 0x002D],
            [Consonant_Preceding_Repha => 0x0D4E], [Consonant_Prefixed => 0x111C2],
            [Consonant_Subjoined => 0x0F8D], [Consonant_Succeeding_Repha => 0x17CC],
            [Consonant_With_Stacker => 0x0CF1], [Gemination_Mark => 0x0A71],
            [Invisible_Stacker => 0x1039], [Joiner => 0x200D],
            [Modifying_Letter => 0x0B83], [Non_Joiner => 0x200C],
            [Nukta => 0x093C], [Number => 0x0030],
            [Number_Joiner => 0x1107F], [Other => 0x0041],
            [Pure_Killer => 0x0D3B], [Register_Shifter => 0x17C9],
            [Reordering_Killer => 0x1BF2], [Syllable_Modifier => 0x00B2],
            [Tone_Letter => 0x1970], [Tone_Mark => 0x0E48],
            [Virama => 0x094D], [Visarga => 0x0903],
            [Vowel => 0x1963], [Vowel_Dependent => 0x093A],
            [Vowel_Independent => 0x0904],
        ],
    },
);

sub compiles {
    my ($source) = @_;
    local $SIG{__WARN__} = sub { };
    return defined eval "qr/$source/";
}

sub matches {
    my ($char, $source) = @_;
    local $SIG{__WARN__} = sub { };
    my $rx = eval "qr/$source/";
    return defined($rx) && $char =~ $rx;
}

for my $property (@properties) {
    for my $entry (@{$property->{values}}) {
        my ($short_value, $cp, $long_value) = @$entry;
        $long_value //= $short_value;
        my $char = chr($cp);
        # Perl's ordinary property-value parser uses the short NA spelling;
        # Not_Applicable remains the canonical UCD/wildcard value name.
        my $ordinary_value = $short_value eq 'NA' ? 'NA' : $long_value;
        for my $property_name ($property->{short}, $property->{long}) {
            my $source = "\\p{$property_name=$ordinary_value}";
            my $rx = eval "qr/$source/";
            ok(defined($rx) && $char =~ $rx,
                "$property_name=$ordinary_value contains U+" . sprintf('%04X', $cp));
        }
        my $short_rx = eval "qr/\\p{$property->{short}=$short_value}/";
        ok(defined($short_rx) && $char =~ $short_rx,
            "$property->{short} short value $short_value resolves");
    }
}

ok(matches("\x{093C}", '\\p{ i n-p_c = b_o t-t o m }'),
    'property and value aliases use Perl loose matching');
ok(matches("\x{0915}", '\\p{I n_S-C = c-o_n s o n a n t}'),
    'syllabic category accepts loose spelling');
ok(matches("\x{093F}", '\\p{Is_InPC=Left}'),
    'exact-case Is prefix accepts a short property alias');
ok(matches("\x{0915}", '\\p{IsIndic_Syllabic_Category=Consonant}'),
    'exact-case Is prefix accepts a long property alias');

ok(!compiles('\\p{is_InPC=Left}'), 'lowercase is prefix is rejected');
ok(!compiles('\\p{IS_InSC=Consonant}'), 'uppercase IS prefix is rejected');
SKIP: {
    skip 'bare enum names changed in Perl 5.44', 2 if $] < 5.044;
    ok(!compiles('\\p{InPC}'), 'bare positional property is rejected');
    ok(!compiles('\\p{InSC}'), 'bare syllabic property is rejected');
}
ok(!compiles('\\p{InPC=Unknown_Value}'), 'unknown positional value is rejected');
ok(!compiles('\\p{InSC=Unknown_Value}'), 'unknown syllabic value is rejected');
ok(!compiles('\\p{InPC=Not_Applicable}'),
    'ordinary property syntax rejects the long default value spelling');
ok(!compiles("\\p{Indic\x{1680}Positional_Category=Left}"),
    'Unicode whitespace is not ignored by loose matching');

my $cp = "\x{093F}";
ok(matches($cp, '\\p{InPC=Left}'), 'positive property matches');
ok(!matches($cp, '\\P{InPC=Left}'), 'property complement rejects member');
ok(!matches($cp, '\\p{^InPC=Left}'), 'leading caret complements property');
ok(matches($cp, '\\P{^InPC=Left}'), 'P plus leading caret double-negates');

require Unicode::UCD;
my $unicode_version = Unicode::UCD::UnicodeVersion();
SKIP: {
    skip 'requires Unicode 17.0 data', 4 unless $unicode_version ge '17.0';
    ok(matches("\x{11B60}", '\\p{InPC=Top}'), 'Unicode 17 Sharada supplement top mark');
    ok(matches("\x{11B61}", '\\p{InPC=Right}'), 'Unicode 17 Sharada supplement right mark');
    ok(matches("\x{11B62}", '\\p{InPC=Bottom}'), 'Unicode 17 Sharada supplement bottom mark');
    ok(matches("\x{11B67}", '\\p{InSC=Vowel_Dependent}'),
        'Unicode 17 Sharada supplement syllabic category');
}

done_testing;
