use strict;
use warnings;
use Test::More;
no warnings 'experimental::uniprop_wildcards';

my $break_probe = eval q{qr/\p{GCB=:\A(?:Extend|EX)\z:}/};
if (!defined $break_probe) {
    plan skip_all => 'pinned Unicode break-property data await resolver integration';
}

my @values = (
    ['GCB', 'EX',     'Extend',          0x0300],
    ['GCB', 'XX',     'Other',           0x0041],
    ['SB',  'UP',     'Upper',           0x0041],
    ['SB',  'AT',     'ATerm',           0x002E],
    ['WB',  'LE',     'ALetter',         0x0041],
    ['WB',  'Extend', 'Extend',          0x0300],
    ['lb',  'AL',     'Alphabetic',      0x0041],
    ['lb',  'PR',     'Prefix_Numeric',  0x20C1],
);
for my $value (@values) {
    my ($property, $short, $long, $code_point) = @$value;
    my $short_pattern = eval "qr/\\p{$property=$short}/";
    ok(defined $short_pattern, "$property short value $short compiles") or diag($@);
    like(chr($code_point), $short_pattern,
        "$property short value $short matches representative");
    my $long_pattern = eval "qr/\\p{$property=$long}/";
    ok(defined $long_pattern, "$property long value $long compiles") or diag($@);
    like(chr($code_point), $long_pattern,
        "$property long value $long matches representative");
}

my $loose = eval q{qr/\p{grapheme cluster break = e-x t_e n d}/};
ok(defined $loose, 'loose GCB property and value compile') or diag($@);
like(chr(0x0300), $loose, 'loose GCB property and value match');

my $alternate = eval q{qr/\p{Line_Break=Inseperable}/};
ok(defined $alternate, 'alternate Line_Break value Inseperable compiles') or diag($@);
like(chr(0x2024), $alternate, 'Inseperable aliases Inseparable');

my @membership = (
    [0x000D, q{qr/\p{GCB=CR}/}, 1, 'carriage return has GCB CR'],
    [0x000D, q{qr/\p{SB=CR}/},  1, 'carriage return has SB CR'],
    [0x000D, q{qr/\p{WB=CR}/},  1, 'carriage return has WB CR'],
    [0x000D, q{qr/\p{LB=CR}/},  1, 'carriage return has LB CR'],
    [0x20C1, q{qr/\p{LB=PR}/},  1, 'ordered LB currency default applies'],
    [0x3401, q{qr/\p{LB=ID}/},  1, 'ordered LB CJK default applies'],
    [0x1F02C, q{qr/\p{LB=ID}/}, 1, 'ordered LB symbol default applies'],
    [0x40000, q{qr/\p{LB=XX}/}, 1, 'general LB Unknown default applies'],
    [0x0300, q{qr/\p{GCB=EX}/}, 1, 'GCB EX means Extend'],
    [0x0021, q{qr/\p{LB=EX}/},  1, 'LB EX means Exclamation'],
    [0x203F, q{qr/\p{WB=EX}/},  1, 'WB EX means ExtendNumLet'],
    [0x0041, q{qr/\p{GCB=EX}/}, 0, 'letter is not GCB Extend'],
);
for my $membership (@membership) {
    my ($code_point, $source, $expected, $description) = @$membership;
    my $pattern = eval $source;
    ok(defined $pattern, "$description compiles") or diag($@);
    is(chr($code_point) =~ $pattern ? 1 : 0, $expected, $description);
}

my @accepted_syntax = (
    [q{qr/\p{GCB=Other}/}, 'GCB equals', 'A'],
    [q{qr/\p{GCB:Other}/}, 'GCB colon', 'A'],
    [q{qr/\p{Sentence_Break=Upper}/}, 'Sentence_Break equals', 'A'],
    [q{qr/\p{SB:Upper}/}, 'SB colon', 'A'],
    [q{qr/\p{Word_Break=ALetter}/}, 'Word_Break equals', 'A'],
    [q{qr/\p{WB:ALetter}/}, 'WB colon', 'A'],
    [q{qr/\p{Is_LB=Alphabetic}/}, 'Is-LB equals', 'A'],
    [q{qr/\p{Is_LB:Alphabetic}/}, 'Is-LB colon', 'A'],
    [q{qr/\p{IsUpper}/}, 'bare Is prefix', 'A'],
);
for my $accepted (@accepted_syntax) {
    my ($source, $description, $subject) = @$accepted;
    my $pattern = eval $source;
    ok(defined $pattern, "$description compiles") or diag($@);
    like($subject, $pattern, "$description matches");
}

my @accepted_wildcards = (
    [q{qr/\p{GCB=:\A(?:Extend|EX)\z:}/}, 0x0300, 'GCB wildcard'],
    [q{qr/\p{SB=:\A(?:Upper|UP)\z:}/}, 0x0041, 'SB wildcard'],
    [q{qr/\p{WB=:\A(?:ALetter|LE)\z:}/}, 0x0041, 'WB wildcard'],
    [q{qr/\p{LB=:\A(?:Inseparable|Inseperable)\z:}/}, 0x2024,
        'alternate LB wildcard'],
);
for my $wildcard (@accepted_wildcards) {
    my ($source, $code_point, $description) = @$wildcard;
    my $pattern = eval $source;
    ok(defined $pattern, "$description compiles") or diag($@);
    like(chr($code_point), $pattern, "$description matches");
}

my @rejected = (
    [q{qr/\p{Is_GCB=:\AExtend\z:}/},
        qr/Can't find Unicode property definition/, 'Is-prefixed wildcard'],
    [q{qr/\p{GCB=}/},
        qr/Unicode property wildcard not terminated/, 'missing GCB value'],
    [q{qr/\p{GCB=:\A.*\z:}/},
        qr/quantifier '\*' is not allowed/i, 'star-quantifier wildcard'],
);
for my $rejected (@rejected) {
    my ($source, $error_pattern, $description) = @$rejected;
    my $pattern = eval $source;
    ok(!defined $pattern, "$description is rejected");
    like($@, $error_pattern, "$description reports the expected diagnostic");
}

done_testing();
