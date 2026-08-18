use strict;
use warnings;
use utf8;
use Test::More;
use Unicode::UCD ();

no warnings 'experimental::uniprop_wildcards';

sub compile_property {
    my ($sigil, $body) = @_;
    my $source = 'qr/\\' . $sigil . '{' . $body . '}/';
    my $regex = eval $source;
    return ($regex, $@);
}

sub compiles_ok {
    my ($body, $name, $sigil) = @_;
    $sigil //= 'p';
    my ($regex, $error) = compile_property($sigil, $body);
    ok(defined $regex && $error eq '', $name) or diag $error;
    return $regex;
}

sub compile_fails {
    my ($body, $name, $sigil) = @_;
    $sigil //= 'p';
    my ($regex, $error) = compile_property($sigil, $body);
    ok(!defined $regex && $error ne '', $name);
}

sub property_matches {
    my ($body, $cp, $expected, $name, $sigil) = @_;
    $sigil //= 'p';
    my ($regex, $error) = compile_property($sigil, $body);
    if (!defined $regex) {
        fail($name);
        diag $error;
        return;
    }
    is(chr($cp) =~ $regex ? 1 : 0, $expected, $name);
}

TODO: {
local $TODO = 'HST and Quick_Check resolver wiring is a separate Phase 36 slice';

my @hst_values = (
    [ L   => 'Leading_Jamo',   0x1100, 0x1161 ],
    [ V   => 'Vowel_Jamo',     0x1161, 0x11A8 ],
    [ T   => 'Trailing_Jamo',  0x11A8, 0x1100 ],
    [ LV  => 'LV_Syllable',    0xAC00, 0xAC01 ],
    [ LVT => 'LVT_Syllable',   0xAC01, 0xAC00 ],
    [ NA  => 'Not_Applicable', 0x0041, 0xAC00 ],
);

for my $value (@hst_values) {
    my ($short, $long, $member, $nonmember) = @$value;
    property_matches("hst=$short", $member, 1, "hst short value $short");
    property_matches("Hangul_Syllable_Type=$long", $member, 1,
        "hst long value $long");
    property_matches("hst=$short", $nonmember, 0,
        "hst $short excludes representative nonmember");
}

my @qc_properties = (
    [ NFC_QC  => 'NFC_Quick_Check',  [ M => 'Maybe', 0x0301, 0x0041 ],
                                      [ N => 'No',    0x0340, 0x0041 ],
                                      [ Y => 'Yes',   0x0041, 0x0340 ] ],
    [ NFD_QC  => 'NFD_Quick_Check',  [ N => 'No',    0x00C0, 0x0041 ],
                                      [ Y => 'Yes',   0x0041, 0x00C0 ] ],
    [ NFKC_QC => 'NFKC_Quick_Check', [ M => 'Maybe', 0x0301, 0x0041 ],
                                      [ N => 'No',    0xFB01, 0x0041 ],
                                      [ Y => 'Yes',   0x0041, 0xFB01 ] ],
    [ NFKD_QC => 'NFKD_Quick_Check', [ N => 'No',    0xFB01, 0x0041 ],
                                      [ Y => 'Yes',   0x0041, 0xFB01 ] ],
);

for my $property (@qc_properties) {
    my ($short_property, $long_property, @values) = @$property;
    for my $value (@values) {
        my ($short, $long, $member, $nonmember) = @$value;
        property_matches("$short_property=$short", $member, 1,
            "$short_property short value $short");
        property_matches("$long_property=$long", $member, 1,
            "$long_property long value $long");
        property_matches("$short_property=$short", $nonmember, 0,
            "$short_property $short excludes representative nonmember");
    }
}

# The same ordinary property/value parser accepts either separator and applies
# Unicode loose matching (ASCII case, whitespace, underscore, and hyphen).
property_matches('hst:L', 0x1100, 1, 'colon is an ordinary value separator');
property_matches('N F C - Q C : m a y b e', 0x0301, 1,
    'property and value use loose matching');
property_matches('H A N G U L - S Y L L A B L E _ T Y P E = l e a d i n g - j a m o',
    0x1100, 1, 'long aliases use loose matching');

# Is is a literal, case-sensitive compatibility prefix.  Separators after it
# remain part of the loosely matched property name.
property_matches('Ishst=L', 0x1100, 1, 'exact Is prefix without separator');
property_matches('Is_hst=L', 0x1100, 1, 'exact Is prefix with loose separator');
property_matches('IsNFC_Quick_Check=Maybe', 0x0301, 1,
    'exact Is prefix on quick-check alias');
compile_fails('isHST=L', 'lowercase is is not the Is prefix');
compile_fails('IShst=L', 'uppercase IS is not the Is prefix');

# p/P and a leading caret each complement the selected value; using both
# performs the two complements and returns the original set.
property_matches('hst=L', 0x1100, 0, 'P complements a member', 'P');
property_matches('hst=L', 0x0041, 1, 'P includes a nonmember', 'P');
property_matches('^hst=L', 0x1100, 0, 'caret complements a member');
property_matches('^hst=L', 0x0041, 1, 'caret includes a nonmember');
property_matches('^hst=L', 0x1100, 1, 'P plus caret restores member', 'P');
property_matches('^hst=L', 0x0041, 0, 'P plus caret restores exclusion', 'P');

# Wildcards match the official short and long aliases, are case-insensitive by
# default, and union every value whose alias matches.  They are unavailable
# through Is.  An unmatched wildcard is a compile error, not an empty set.
property_matches('hst=:\AL\z:', 0x1100, 1, 'wildcard matches short alias');
property_matches('hst=:\Aleading_jamo\z:', 0x1100, 1,
    'wildcard matches long alias case-insensitively');
property_matches('NFC_QC=:\A(?:No|Maybe)\z:', 0x0340, 1,
    'wildcard alternation unions No');
property_matches('NFC_QC=:\A(?:No|Maybe)\z:', 0x0301, 1,
    'wildcard alternation unions Maybe');
property_matches('NFC_QC=:\A(?:No|Maybe)\z:', 0x0041, 0,
    'wildcard alternation excludes Yes');
compile_fails('Is_hst=:\AL\z:', 'wildcards are rejected through Is');
compile_fails('hst=:L.*:', 'star quantifier is rejected in wildcard');
compile_fails('hst=:\ANoSuchValue\z:', 'unmatched wildcard is rejected');

# Enumerated properties have no bare form.  Boolean aliases are not silently
# accepted as enumerated values, and properties without Maybe reject it.
for my $bare (qw(hst Hangul_Syllable_Type NFC_QC NFC_Quick_Check)) {
    compile_fails($bare, "bare enumerated property $bare is rejected");
}
for my $invalid ('hst=True', 'hst=N', 'NFC_QC=True', 'NFC_QC=False',
                 'NFD_QC=M', 'NFKD_QC=Maybe', 'NFC_QC=NoSuchValue') {
    compile_fails($invalid, "invalid enumerated form $invalid is rejected");
}

# Defaults cover code points absent from the source files, including
# unassigned/non-scalar code points within the Unicode code-space.
for my $cp (0x0041, 0xD800, 0x10FFFF) {
    property_matches('hst=NA', $cp, 1, sprintf('hst default NA at U+%04X', $cp));
    for my $property (qw(NFC_QC NFD_QC NFKC_QC NFKD_QC)) {
        property_matches("$property=Y", $cp, 1,
            sprintf('%s default Y at U+%04X', $property, $cp));
    }
}

# A wildcard covering every legal value is the full Unicode set.
my $all_hst = 'hst=:\A(?:L|V|T|LV|LVT|NA)\z:';
for my $cp (0x0041, 0x1100, 0x1161, 0x11A8, 0xAC00, 0xAC01, 0xD800, 0x10FFFF) {
    property_matches($all_hst, $cp, 1,
        sprintf('all hst values cover U+%04X', $cp));
}
my $all_nfc = 'NFC_QC=:\A(?:M|N|Y)\z:';
for my $cp (0x0041, 0x0301, 0x0340, 0xD800, 0x10FFFF) {
    property_matches($all_nfc, $cp, 1,
        sprintf('all NFC_QC values cover U+%04X', $cp));
}

# Unicode 17 assigned MODIFIER LETTER CAPITAL S (U+A7F1) a compatibility
# decomposition.  Perl builds with older UCDs correctly see the old default Y.
my ($unicode_major) = Unicode::UCD::UnicodeVersion() =~ /\A(\d+)/;
my $a7f1_expected = $unicode_major >= 17 ? 1 : 0;
property_matches('NFKC_QC=N', 0xA7F1, $a7f1_expected,
    'U+A7F1 NFKC_QC follows bundled Unicode version');
property_matches('NFKD_QC=N', 0xA7F1, $a7f1_expected,
    'U+A7F1 NFKD_QC follows bundled Unicode version');
property_matches('NFC_QC=Y', 0xA7F1, 1, 'U+A7F1 remains NFC_QC Yes');
property_matches('NFD_QC=Y', 0xA7F1, 1, 'U+A7F1 remains NFD_QC Yes');
}

done_testing();
