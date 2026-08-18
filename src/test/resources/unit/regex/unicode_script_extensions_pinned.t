use strict;
use warnings;
use Test::More;
no warnings 'experimental::uniprop_wildcards';

my $script_probe = eval q{qr/\p{scx=:\ALat(in)?\z:}/};
if (!defined $script_probe) {
    plan skip_all => 'pinned Script and Script_Extensions data await resolver integration';
}

my @values = (
    ['Latn', 'Latin',     0x0041],
    ['Grek', 'Greek',     0x03B1],
    ['Copt', 'Coptic',    0x03E2],
    ['Zyyy', 'Common',    0x0030],
    ['Zinh', 'Inherited', 0x0300],
    ['Zzzz', 'Unknown',   0x0378],
);
for my $value (@values) {
    my ($short, $long, $code_point) = @$value;
    my $short_pattern = eval "qr/\\p{sc=$short}/";
    ok(defined $short_pattern, "short Script value $short compiles") or diag($@);
    like(chr($code_point), $short_pattern,
        "short Script value $short matches representative");
    my $long_pattern = eval "qr/\\p{Script=$long}/";
    ok(defined $long_pattern, "long Script value $long compiles") or diag($@);
    like(chr($code_point), $long_pattern,
        "long Script value $long matches representative");
}

my $loose = eval q{qr/\p{script = c-a u_c a s i a n albanian}/};
ok(defined $loose, 'loose Script property and value compile') or diag($@);
like(chr(0x10530), $loose, 'loose Script property and value match');

for my $alternate (
    [q{qr/\p{sc=Qaac}/}, 0x03E2, 'Qaac aliases Coptic'],
    [q{qr/\p{sc=Qaai}/}, 0x0300, 'Qaai aliases Inherited'],
) {
    my ($source, $code_point, $description) = @$alternate;
    my $pattern = eval $source;
    ok(defined $pattern, "$description compiles") or diag($@);
    like(chr($code_point), $pattern, $description);
}

my @membership = (
    [0x00B7, q{qr/\p{sc=Common}/}, 1, 'middle dot Script is Common'],
    [0x00B7, q{qr/\p{scx=Latin}/}, 1, 'middle dot Script_Extensions includes Latin'],
    [0x00B7, q{qr/\p{scx=Greek}/}, 1, 'middle dot Script_Extensions includes Greek'],
    [0x00B7, q{qr/\p{scx=Common}/}, 0, 'middle dot scx override excludes Common'],
    [0x0300, q{qr/\p{sc=Inherited}/}, 1, 'combining grave Script is Inherited'],
    [0x0300, q{qr/\p{scx=Latin}/}, 1, 'combining grave Script_Extensions includes Latin'],
    [0x0300, q{qr/\p{scx=Inherited}/}, 0, 'combining grave scx override excludes Inherited'],
    [0x0041, q{qr/\p{scx=Latin}/}, 1, 'non-overridden scx falls back to Script'],
    [0x0378, q{qr/\p{scx=Unknown}/}, 1, 'unassigned scx falls back to Unknown Script'],
    [0x0041, q{qr/[\p{sc=Latin}]/}, 1, 'Script assignment works inside a class'],
    [0x00B7, q{qr/[\p{scx=Greek}]/}, 1, 'Script_Extensions works inside a class'],
    [0x00B7, q{qr/[\p{scx:Greek}]/}, 1, 'scx colon works inside a class'],
);
for my $membership (@membership) {
    my ($code_point, $source, $expected, $description) = @$membership;
    my $pattern = eval $source;
    ok(defined $pattern, "$description compiles") or diag($@);
    is(chr($code_point) =~ $pattern ? 1 : 0, $expected, $description);
}

my @accepted_syntax = (
    [q{qr/\p{sc=Latin}/}, 'sc equals'],
    [q{qr/\p{sc:Latin}/}, 'sc colon'],
    [q{qr/\p{scx=Latin}/}, 'scx equals'],
    [q{qr/\p{scx:Latin}/}, 'scx colon'],
    [q{qr/\p{Is_sc=Latin}/}, 'Is-sc equals'],
    [q{qr/\p{Is_sc:Latin}/}, 'Is-sc colon'],
    [q{qr/\p{Is_scx=Latin}/}, 'Is-scx equals'],
    [q{qr/\p{IsLatin}/}, 'bare Is prefix'],
);
for my $accepted (@accepted_syntax) {
    my ($source, $description) = @$accepted;
    my $pattern = eval $source;
    ok(defined $pattern, "$description compiles") or diag($@);
    like('A', $pattern, "$description matches");
}

my @accepted_wildcards = (
    [q{qr/\p{sc=:\ALat(in)?\z:}/}, 0x0041, 'Script wildcard'],
    [q{qr/\p{scx=:\ALat(in)?\z:}/}, 0x00B7, 'Script_Extensions wildcard'],
    [q{qr/\p{sc=:\AQaac\z:}/}, 0x03E2, 'alternate Script wildcard'],
);
for my $wildcard (@accepted_wildcards) {
    my ($source, $code_point, $description) = @$wildcard;
    my $pattern = eval $source;
    ok(defined $pattern, "$description compiles") or diag($@);
    like(chr($code_point), $pattern, "$description matches");
}

my @rejected = (
    [q{qr/\p{Is_sc=:\ALatin\z:}/},
        qr/Can't find Unicode property definition/, 'Is-prefixed wildcard'],
    [q{qr/\p{sc=}/},
        qr/Unicode property wildcard not terminated/, 'missing Script value'],
    [q{qr/\p{sc=:\A.*\z:}/},
        qr/quantifier '\*' is not allowed/i, 'star-quantifier wildcard'],
);
for my $rejected (@rejected) {
    my ($source, $error_pattern, $description) = @$rejected;
    my $pattern = eval $source;
    ok(!defined $pattern, "$description is rejected");
    like($@, $error_pattern, "$description reports the expected diagnostic");
}

done_testing();
