use strict;
use warnings;
use utf8;
use Test::More;
no warnings 'experimental::uniprop_wildcards';

local $TODO = 'Core binary property resolver wiring is a separate Phase 36 slice';

sub compile_property {
    my ($property, $sigil) = @_;
    $sigil //= 'p';
    local $SIG{__WARN__} = sub { };
    my $source = 'qr~\\' . $sigil . '{' . $property . '}~';
    my $regex = eval $source;
    return ($regex, $@);
}

sub property_matches {
    my ($property, $cp, $sigil) = @_;
    my ($regex, $error) = compile_property($property, $sigil);
    return 0 if !$regex;
    local $SIG{__WARN__} = sub { };
    return chr($cp) =~ $regex ? 1 : 0;
}

sub property_rejected {
    my ($property, $label) = @_;
    my ($regex, $error) = compile_property($property);
    ok(!$regex && length $error, $label // "$property is rejected") or diag $error;
}

my @prop_list = qw(
    White_Space Bidi_Control Join_Control Dash Quotation_Mark
    Terminal_Punctuation Hex_Digit Ideographic Diacritic Extender
    Noncharacter_Code_Point IDS_Binary_Operator IDS_Trinary_Operator Radical
    Unified_Ideograph Deprecated Soft_Dotted Logical_Order_Exception
    Sentence_Terminal Variation_Selector Pattern_White_Space Pattern_Syntax
    Prepended_Concatenation_Mark Regional_Indicator
);

my @derived_core = qw(
    Alphabetic Lowercase Uppercase Cased Case_Ignorable
    Changes_When_Lowercased Changes_When_Uppercased Changes_When_Titlecased
    Changes_When_Casefolded Changes_When_Casemapped
    Changes_When_NFKC_Casefolded Default_Ignorable_Code_Point
    Grapheme_Base Grapheme_Extend Math ID_Start ID_Continue XID_Start XID_Continue
);

my @derived_binary = qw(
    Bidi_Mirrored Composition_Exclusion Full_Composition_Exclusion
);

my @emoji = qw(
    Emoji Emoji_Presentation Emoji_Modifier Emoji_Modifier_Base Emoji_Component
);

my @core = (@prop_list, @derived_core, @derived_binary, @emoji);
is(scalar @core, 51, 'selected unresolved core contains exactly 51 binary properties');
for my $property (@core) {
    my ($regex, $error) = compile_property($property);
    ok($regex, "$property compiles in the positive single form") or diag $error;
}

# Representative long/short aliases from each source family.
for my $case (
    ['Noncharacter_Code_Point', 'NChar', 0xFDD0, 'PropList'],
    ['Uppercase', 'Upper', 0x0041, 'DerivedCoreProperties'],
    ['Changes_When_NFKC_Casefolded', 'CWKCF', 0x0041, 'DerivedCoreProperties normalization'],
    ['Bidi_Mirrored', 'Bidi_M', 0x0028, 'DBinaryProperties'],
    ['Composition_Exclusion', 'CE', 0x0958, 'CompositionExclusions'],
    ['Full_Composition_Exclusion', 'Comp_Ex', 0x0340, 'DerivedNormalizationProps'],
    ['Emoji_Presentation', 'EPres', 0x231A, 'emoji-data'],
    ['Emoji_Modifier', 'EMod', 0x1F3FB, 'emoji-data modifier'],
) {
    my ($long, $short, $cp, $family) = @$case;
    ok(property_matches($long, $cp), "$family long alias $long matches its representative");
    ok(property_matches($short, $cp), "$family short alias $short is equivalent");
}

# Unicode binary value aliases: every true and false spelling, and \P complement.
for my $value (qw(Yes Y True T)) {
    ok(property_matches("Uppercase=$value", 0x41), "Uppercase=$value accepts the true alias");
    ok(property_matches("Uppercase=$value", 0x61, 'P'), "P{Uppercase=$value} is its complement");
}
for my $value (qw(No N False F)) {
    ok(property_matches("Uppercase=$value", 0x61), "Uppercase=$value accepts the false alias");
    ok(property_matches("Uppercase=$value", 0x41, 'P'), "P{Uppercase=$value} complements false back to true");
}
property_rejected('Uppercase=1', 'numeric true is not a binary value alias');
property_rejected('Uppercase=0', 'numeric false is not a binary value alias');

ok(property_matches('^Uppercase', 0x61), 'p{^Uppercase} negates the property');
ok(property_matches('^Uppercase', 0x41, 'P'), 'P{^Uppercase} double-negates the property');
ok(property_matches('^Uppercase=Y', 0x61), 'caret negates an explicit true value');
ok(property_matches('^Uppercase=N', 0x41), 'caret negates an explicit false value');
property_rejected('^^Uppercase', 'only one leading caret is recognized');

# Perl's exact Is policy: prefix a real lhs, but do not invent Is as a lhs.
ok(property_matches('IsUppercase', 0x41), 'single IsUppercase form is accepted');
ok(property_matches('Is_Uppercase', 0x41), 'single Is_Uppercase form is accepted');
ok(property_matches('IsUppercase=Y', 0x41), 'compound IsUppercase=Y form is accepted');
ok(property_matches('Is_Uppercase:Y', 0x41), 'compound Is_Uppercase:Y form is accepted');
property_rejected('Is=Uppercase', 'Is=Uppercase is not a Perl compound property');
property_rejected('Is:Uppercase=Y', 'Is:Uppercase=Y is not a Perl compound property');
property_rejected("\x{130}D_Start", 'non-ASCII case folding is not used for property aliases');

ok(property_matches('up-per case = yes', 0x41), 'loose matching ignores case, spaces, and hyphens');
ok(property_matches('u_p p-e r c-a-s-e = y-e-s', 0x41), 'loose matching ignores separators in aliases');
ok(property_matches('non character code point = t', 0xFDD0), 'loose matching applies to long binary names and values');

# Wildcards work on an ordinary binary lhs, but retain Perl's syntax limits.
ok(property_matches('Uppercase=/y/', 0x41), 'binary property value wildcard is accepted and defaults to /i');
ok(property_matches('Uppercase=/\A(?:Y|Yes)\z/', 0x41), 'binary wildcard may select true aliases');
property_rejected('Is_Uppercase=/y/', 'wildcards are unavailable through an Is_ lhs');
property_rejected('Uppercase=/Y.*/', 'star quantifier is rejected in a property wildcard');
property_rejected('Uppercase=/\pL/', 'nested Unicode properties are rejected in a property wildcard');
property_rejected('Uppercase=/y/i', 'modifiers may not follow the wildcard delimiter');

# Defaults matter for unassigned and permanently reserved scalar values.
ok(property_matches('Uppercase=N', 0x0378), 'binary false includes an ordinary unassigned code point');
ok(property_matches('Default_Ignorable_Code_Point', 0x2065), 'derived default includes unassigned U+2065');
ok(!property_matches('Default_Ignorable_Code_Point', 0x0378), 'derived default excludes unrelated unassigned U+0378');
ok(property_matches('Noncharacter_Code_Point', 0xFDD0), 'noncharacter range includes U+FDD0');
ok(property_matches('Noncharacter_Code_Point', 0x10FFFF), 'plane-end U+10FFFF is a noncharacter');
ok(!property_matches('Noncharacter_Code_Point', 0x0378), 'ordinary unassigned U+0378 is not a noncharacter');

my ($hyphen_regex, $hyphen_error) = compile_property('Hyphen');
ok($hyphen_regex, 'deprecated Unicode Hyphen property remains accepted by Perl') or diag $hyphen_error;
ok(property_matches('Hyphen', 0x00AD), 'deprecated Hyphen property retains its membership');

for my $property (qw(
    Other_Alphabetic OAlpha Other_Default_Ignorable_Code_Point ODI
    Other_Grapheme_Extend OGr_Ext Other_ID_Continue OIDC Other_ID_Start OIDS
    Other_Lowercase OLower Other_Math OMath Other_Uppercase OUpper
)) {
    property_rejected($property, "contributory property $property is intentionally rejected");
}

for my $property (qw(
    Expands_On_NFC XO_NFC Expands_On_NFD XO_NFD
    Expands_On_NFKC XO_NFKC Expands_On_NFKD XO_NFKD
)) {
    property_rejected($property, "deprecated normalization property $property is intentionally rejected");
}

done_testing();
