use strict;
use warnings;
use utf8;
use Test::More;

sub compile_property {
    my ($sigil, $body) = @_;
    my $warnings = '';
    local $SIG{__WARN__} = sub { $warnings .= join '', @_ };
    my $source = sprintf 'qr~\\A\\%s{%s}\\z~u', $sigil, $body;
    my $regex = eval $source;
    return ($regex, $@, $warnings);
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

sub compile_fails {
    my ($body, $name, $sigil) = @_;
    $sigil //= 'p';
    my ($regex, $error) = compile_property($sigil, $body);
    ok(!defined($regex) && length($error), $name);
}

sub warning_like {
    my ($body, $pattern, $name) = @_;
    my (undef, undef, $warnings) = compile_property('p', $body);
    like($warnings, $pattern, $name);
}

sub error_like {
    my ($body, $pattern, $name) = @_;
    my (undef, $error) = compile_property('p', $body);
    like($error, $pattern, $name);
}

TODO: {
local $TODO = 'kEH_Core resolver wiring is a separate Phase 36 slice';

# Exact Unicode 17 partition boundaries.  Each point is checked against every
# value, so the assertions prove both membership and pairwise exclusion.
my @classification = (
    [0x12fff, 'N'], [0x13000, 'C'], [0x1305c, 'C'],
    [0x1305d, 'L'], [0x1305e, 'L'], [0x1305f, 'C'],
    [0x130a9, 'C'], [0x13423, 'C'], [0x13424, 'L'],
    [0x13425, 'C'], [0x136ae, 'N'], [0x142ad, 'N'],
    [0x143f9, 'N'], [0x143fa, 'C'], [0x143fb, 'N'],
    [0xd800, 'N'], [0x10ffff, 'N'],
);
for my $case (@classification) {
    my ($cp, $expected) = @$case;
    for my $value (qw(C L N)) {
        property_matches("kEH_Core=$value", $cp, $value eq $expected ? 1 : 0,
            sprintf('U+%04X has kEH_Core=%s truth value', $cp, $value));
    }
}

# \p/\P and a single inner caret are independent complements.
my @representative = (
    [C => 0x143fa, 0x143fb],
    [L => 0x13424, 0x13425],
    [N => 0x143fb, 0x143fa],
);
for my $case (@representative) {
    my ($value, $member, $nonmember) = @$case;
    property_matches("kEH_Core=$value", $member, 1, "$value p member");
    property_matches("kEH_Core=$value", $nonmember, 0, "$value p nonmember");
    property_matches("^kEH_Core=$value", $member, 0, "$value caret member");
    property_matches("^kEH_Core=$value", $nonmember, 1, "$value caret nonmember");
    property_matches("kEH_Core=$value", $member, 0, "$value P member", 'P');
    property_matches("kEH_Core=$value", $nonmember, 1, "$value P nonmember", 'P');
    property_matches("^kEH_Core=$value", $member, 1, "$value P caret member", 'P');
    property_matches("^kEH_Core=$value", $nonmember, 0,
        "$value P caret nonmember", 'P');
}

# The provisional property has one intrinsic name, ASCII loose matching, both
# assignment separators, and Perl's exact-case Is compatibility prefix.
for my $case (@representative) {
    my ($value, $member, $nonmember) = @$case;
    my $lower = lc $value;
    my @spelling = (
        "kEH_Core=$value",
        "kehcore=$lower",
        "k-e h_c o r e = $value",
        "kEH_Core: $lower",
        "Is_kEH_Core=$value",
        "IskEHCore=$lower",
    );
    for my $body (@spelling) {
        property_matches($body, $member, 1, "$body accepts its member");
    }
    for my $body (@spelling) {
        property_matches($body, $nonmember, 0, "$body excludes its nonmember");
    }
}

# Wildcards operate over precisely C/L/N and are case-insensitive by default.
for my $case (@representative) {
    my ($value, $member) = @$case;
    property_matches("kEH_Core=:\\A$value\\z:", $member, 1,
        "colon wildcard selects $value");
    property_matches("kEH_Core=/\\A$value\\z/", $member, 1,
        "slash wildcard selects $value");
    property_matches('kEH_Core=:\\A' . lc($value) . '\\z:', $member, 1,
        "wildcard selects lowercase $value");
}
my $core_or_legacy = 'kEH_Core=:\\A(?:C|L)\\z:';
property_matches($core_or_legacy, 0x143fa, 1, 'wildcard union includes C');
property_matches($core_or_legacy, 0x13424, 1, 'wildcard union includes L');
property_matches($core_or_legacy, 0x143fb, 0, 'wildcard union excludes N');

my $all_values = 'kEH_Core=:\\A(?:C|L|N)\\z:';
for my $cp (0x0000, 0x12fff, 0x13000, 0x1305d, 0x130a9, 0x13424,
            0x136ae, 0x142ad, 0x143fa, 0xd800, 0x10ffff) {
    property_matches($all_values, $cp, 1,
        sprintf('all wildcard values cover U+%04X', $cp));
}

# Exact Error calls in the contiguous TestProp-02 kEH_Core block.
my @testprop_errors = (
    ['kehcore', 'p'], ['kehcore', 'P'],
    ["kEH_Core=/a/\tC", 'p'], ["kEH_Core=/a/\tC", 'P'],
    ['Is_kEH_Core=_ C:=', 'p'], ['Is_kEH_Core=_ C:=', 'P'],
    ['kEH_Core=__L/a/', 'p'], ['kEH_Core=__L/a/', 'P'],
    ["Is_kEH_Core:\t:=-_l", 'p'], ["Is_kEH_Core:\t:=-_l", 'P'],
    ['kEH_Core=_ N:=', 'p'], ['kEH_Core=_ N:=', 'P'],
    ['Is_kEH_Core= _N/a/', 'p'], ['Is_kEH_Core= _N/a/', 'P'],
);
for my $case (@testprop_errors) {
    compile_fails($case->[0], "$case->[1] rejects malformed $case->[0]", $case->[1]);
}

my @policy_errors = (
    ['is_kEH_Core=C', 'lowercase is prefix'],
    ['IS_kEH_Core=C', 'uppercase IS prefix'],
    ["kEH\x{1680}Core=C", 'Unicode whitespace in property name'],
    ["kEH_Core=\x{1680}C", 'Unicode whitespace in value'],
    ['kEH_Core=Core', 'unknown enum value'],
    ['kEH_Core=Y', 'boolean yes value'],
    ['kEH_Core=False', 'boolean false value'],
    ['Is_kEH_Core=:\\AC\\z:', 'wildcard through Is prefix'],
    ['kEH_Core=:\\AX\\z:', 'unmatched wildcard'],
    ['kEH_Core=:C*:', 'wildcard star quantifier'],
    ['kEH_Core:=C', 'doubled assignment separator'],
    ['^^kEH_Core=C', 'double leading caret'],
);
for my $case (@policy_errors) {
    compile_fails($case->[0], "policy rejects $case->[1]");
}

warning_like('kEH_Core=:\\AC\\z:', qr/Unicode property wildcards feature is experimental/,
    'colon wildcard emits experimental warning');
warning_like('kEH_Core=/\\AL\\z/', qr/Unicode property wildcards feature is experimental/,
    'slash wildcard emits experimental warning');
warning_like($core_or_legacy, qr/Unicode property wildcards feature is experimental/,
    'union wildcard emits experimental warning');
warning_like('kEH_Core=:\\AX\\z:', qr/Unicode property wildcards feature is experimental/,
    'unmatched wildcard still emits experimental warning');

error_like('kEH_Core', qr/Can't find Unicode property definition/,
    'bare property uses unknown-definition diagnostic');
error_like('kEH_Core=Core', qr/Can't find Unicode property definition/,
    'unknown value uses unknown-definition diagnostic');
error_like('kEH_Core=:\\AX\\z:', qr/No Unicode property value wildcard matches/,
    'unmatched wildcard reports no value match');
error_like('kEH_Core=:C*:', qr/Use of quantifier '\*' is not allowed/,
    'wildcard star reports forbidden quantifier');
error_like('kEH_Core:=C', qr/Unicode property wildcard not terminated/,
    'double separator reports unterminated wildcard');

}

done_testing();
