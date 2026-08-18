use strict;
use warnings;
use utf8;
use Test::More;
use Unicode::UCD ();

sub compile_property {
    my ($sigil, $property) = @_;
    my @warnings;
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    my $source = 'qr!\\A\\' . $sigil . '{' . $property . '}\\z!u';
    my $re = eval $source;
    return ($re, $@, join('', @warnings), $source);
}

sub property_matches {
    my ($sigil, $property, $code_point, $expected, $name) = @_;
    my ($re, $error, undef, $source) = compile_property($sigil, $property);
    my $got = $re && !$error && chr($code_point) =~ $re ? 1 : 0;
    ok($re && !$error && $got == $expected, $name)
        or diag("source=$source error=$error");
}

sub property_rejected {
    my ($property, $error_pattern, $name) = @_;
    my (undef, $error, undef, $source) = compile_property('p', $property);
    like($error, $error_pattern, $name)
        or diag("source=$source");
}

my $experimental = qr/Unicode property wildcards feature is experimental/;

# Canonical, official-alias, and Perl-loose candidates through both property
# aliases and both wildcard delimiters.
TODO: {
local $TODO = 'Phase 36 Block value-wildcard resolver readiness';

property_matches('p', 'Block=:\\ABasic_Latin\\z:', 0x0041, 1,
    'Block colon wildcard matches a canonical value');
property_matches('p', 'Block=:\\ABasic_Latin\\z:', 0x0080, 0,
    'Block colon wildcard excludes a nonmember');
property_matches('p', 'block=:\\Abasiclatin\\z:', 0x0041, 1,
    'Block wildcard matches a lowercase loose candidate');
property_matches('p', 'Blk=:\\AASCII\\z:', 0x0041, 1,
    'Blk wildcard matches an official alternate value alias');
property_matches('p', 'b_l-k=:\\Alatin1sup\\z:', 0x0080, 1,
    'loose Blk and compact value alias are accepted');
property_matches('p', 'Block=/\\ABasic_Latin\\z/', 0x0041, 1,
    'slash-delimited Block wildcard is accepted');
property_matches('p', 'Blk=/\\Abasiclatin\\z/', 0x0041, 1,
    'slash-delimited Blk wildcard uses loose matching');
property_matches('p', 'Block=:\\AGreek_And_Coptic\\z:', 0x0378, 1,
    'wildcard preserves unassigned members of a block');
property_matches('p', 'Block=:\\ANo_Block\\z:', 0x2FE0, 1,
    'wildcard selects the default No_Block value');
property_matches('p', 'Blk=:\\ANB\\z:', 0x2FE0, 1,
    'wildcard selects the official NB alias');

# Multiple matching values are unioned.
my $basic_or_greek = 'Block=:\\A(?:Basic_Latin|Greek_And_Coptic)\\z:';
property_matches('p', $basic_or_greek, 0x0041, 1,
    'wildcard union includes Basic_Latin');
property_matches('p', $basic_or_greek, 0x0378, 1,
    'wildcard union includes Greek_And_Coptic');
property_matches('p', $basic_or_greek, 0x0400, 0,
    'wildcard union excludes an unselected block');

# Outer \P and one inner caret compose by XOR after wildcard set selection.
my @complements = (
    [p => 'Block=:\\ABasic_Latin\\z:', 0x0041, 1, '\\p member'],
    [p => 'Block=:\\ABasic_Latin\\z:', 0x0080, 0, '\\p nonmember'],
    [p => '^Block=:\\ABasic_Latin\\z:', 0x0041, 0, 'inner caret member'],
    [p => '^Block=:\\ABasic_Latin\\z:', 0x0080, 1, 'inner caret nonmember'],
    [P => 'Block=:\\ABasic_Latin\\z:', 0x0041, 0, '\\P member'],
    [P => 'Block=:\\ABasic_Latin\\z:', 0x0080, 1, '\\P nonmember'],
    [P => '^Block=:\\ABasic_Latin\\z:', 0x0041, 1, '\\P caret member'],
    [P => '^Block=:\\ABasic_Latin\\z:', 0x0080, 0, '\\P caret nonmember'],
);
property_matches(@$_) for @complements;

# A recognized wildcard warns before success or no-match failure. Exact-Is
# wildcard rejection happens before warning emission.
for my $case (
    ['Block=:\\ABasic_Latin\\z:', 'successful colon wildcard warns'],
    ['Block=/\\ABasic_Latin\\z/', 'successful slash wildcard warns'],
    [$basic_or_greek, 'successful union wildcard warns'],
    ['Block=:\\ANever_A_Block\\z:', 'unmatched wildcard still warns'],
) {
    my (undef, undef, $warnings) = compile_property('p', $case->[0]);
    like($warnings, $experimental, $case->[1]);
}

}

my (undef, $is_error, $is_warnings) =
    compile_property('p', 'Is_Block=:\\ABasic_Latin\\z:');
like($is_error, qr/Can't find Unicode property definition/,
    'exact-Is wildcard is rejected as an unknown property definition');
unlike($is_warnings, $experimental,
    'exact-Is wildcard rejection does not emit the experimental warning');

property_rejected('Block=:\\ANever_A_Block\\z:',
    qr/No Unicode property value wildcard matches/,
    'wildcard matching no Block value has a specific diagnostic');
property_rejected('Block=:\\ABasic.*\\z:',
    qr/quantifier '\*' is not allowed/i,
    'star quantifier has a specific wildcard diagnostic');
property_rejected('Block:=Basic_Latin',
    qr/Unicode property wildcard not terminated/,
    'doubled separator has the unterminated-wildcard diagnostic');

# Unicode whitespace is not an ASCII loose separator.
property_rejected("Bl\x{1680}ock=Basic_Latin",
    qr/Can't find Unicode property definition/,
    'Unicode whitespace is rejected in the property name');
property_rejected("Block=Basic\x{1680}Latin",
    qr/Can't find Unicode property definition/,
    'Unicode whitespace is rejected in the property value');

# Bare alias collisions stay outside wildcard routing.
SKIP: {
    skip 'InKana rejection is pinned to Unicode 17', 1
        if Unicode::UCD::UnicodeVersion() lt '17.0.0';
    property_rejected('InKana', qr/Can't find Unicode property definition/,
        'ambiguous InKana remains rejected');
}
property_matches('p', 'InGreek', 0x0378, 1,
    'InGreek continues to mean the Greek block');
property_matches('p', 'Greek', 0x1F00, 1,
    'bare Greek continues to mean Script_Extensions');
property_matches('p', 'IsGreek', 0x1F00, 1,
    'bare IsGreek continues to mean Script_Extensions');
property_rejected('Block', qr/Can't find Unicode property definition/,
    'bare Block still requires a value');
property_rejected('Blk', qr/Can't find Unicode property definition/,
    'bare Blk still requires a value');

# These three UTF-16 surrogate block names own a separate 96-assertion
# diagnostic/value-policy residual. Keep representative ordinary forms visible
# so future wildcard work cannot silently conflate the two surfaces.
TODO: {
local $TODO = 'Phase 36 surrogate Block representation readiness';

for my $case (
    ['Block=High_Private_Use_Surrogates', 0xDB80,
        'ordinary High_Private_Use_Surrogates member'],
    ['Blk=High_PU_Surrogates', 0xDB80,
        'ordinary High_PU_Surrogates alias member'],
    ['Is_Block=High_Private_Use_Surrogates', 0xDB80,
        'exact-Is High_Private_Use_Surrogates member'],
    ['Block=High_Surrogates', 0xD800,
        'ordinary High_Surrogates member'],
    ['Blk=High_Surrogates', 0xD800,
        'ordinary Blk High_Surrogates member'],
    ['Is_Block=High_Surrogates', 0xD800,
        'exact-Is High_Surrogates member'],
    ['Block=Low_Surrogates', 0xDC00,
        'ordinary Low_Surrogates member'],
    ['Blk=Low_Surrogates', 0xDC00,
        'ordinary Blk Low_Surrogates member'],
    ['Is_Block=Low_Surrogates', 0xDC00,
        'exact-Is Low_Surrogates member'],
) {
    property_matches('p', $case->[0], $case->[1], 1, $case->[2]);
}

}

done_testing;
