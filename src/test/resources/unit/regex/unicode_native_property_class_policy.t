use strict;
use warnings;
use utf8;
use Test::More;
no warnings qw(experimental::regex_sets experimental::uniprop_wildcards);

my @native = (
    [ccc => '230',                0x0301, 'canonical combining class'],
    [bc  => 'AL',                 0x0627, 'bidi class'],
    [dt  => 'Compat',             0x00A8, 'decomposition type'],
    [ea  => 'W',                  0x1100, 'East Asian width'],
    [nv  => '1',                  0x0031, 'numeric value'],
    [jg  => 'Ain',                0x0639, 'joining group'],
    [WB  => 'LE',                 0x0041, 'word break'],
    [SB  => 'UP',                 0x0041, 'sentence break'],
    [LB  => 'CR',                 0x000D, 'line break'],
    [VO  => 'TR',                 0x2329, 'vertical orientation'],
    [Age => '2.1',                0x20AC, 'Age'],
);

for my $case (@native) {
    my ($property, $value, $code_point, $label) = @$case;
    my $pattern = eval "qr/[\\p{$property=$value}_]/i";
    is($@, '', "$label compiles natively in a standard class");
    ok(chr($code_point) =~ $pattern, "$label retains its member under /i");
}

ok('a' !~ /[\p{SB=UP}_]/i,
    'no-fold sentence-break membership does not gain lowercase under /i');
ok('A' =~ /[\p{Lowercase}_]/i,
    'foldable binary property retains native case closure');
ok('A' !~ /[^\p{Lowercase}]/i,
    'outer class negation retains folded binary membership');
ok('b' =~ /[B\p{SB=UP}]/i,
    'literal beside no-fold property retains its own fold policy');
ok('a' !~ /[B\p{SB=UP}]/i,
    'no-fold property beside foldable literal stays exact');
ok('a' =~ /[\P{SB=UP}]/i && 'A' !~ /[\P{SB=UP}]/i,
    'token-level no-fold property negation remains exact under /i');
ok('a' =~ /[^\p{SB=UP}]/i && 'A' !~ /[^\p{SB=UP}]/i,
    'outer no-fold class negation remains exact under /i');
ok('a' =~ /[\p{PosixUpper}]/i,
    'POSIX compatibility property retains native fold policy');

my $ccc_wildcard = qr/[\p{ccc=:\AAbove\z:}_]/i;
ok("\x{0301}" =~ $ccc_wildcard && 'A' !~ $ccc_wildcard,
    'no-fold wildcard value resolves natively inside a standard class');
my $sentence_wildcard = qr/[\p{SB=:\AUpper\z:}B]/i;
ok('A' =~ $sentence_wildcard && 'a' !~ $sentence_wildcard
        && 'b' =~ $sentence_wildcard,
    'sentence wildcard keeps no-fold property and foldable literal policies');

my @generated_wildcards = (
    ['Block=:\ABasic_Latin\z:', 'A',       'Block wildcard'],
    ['sc=:\ALatin\z:',          'A',       'Script wildcard'],
    ['scx=:\ALatin\z:',         'A',       'Script_Extensions wildcard'],
    ['nv=:\A1\z:',              '1',       'Numeric_Value wildcard'],
    ['jg=:\AAin\z:',            "\x{0639}", 'Joining_Group wildcard'],
    ['Age=:\AV2_1\z:',          "\x{20ac}", 'Age wildcard'],
);
for my $case (@generated_wildcards) {
    my ($property, $member, $label) = @$case;
    my $pattern = eval "qr/[\\p{$property}_]/i";
    is($@, '', "$label compiles inside a standard class");
    ok($member =~ $pattern, "$label retains its selected member");
}

{
    ok('A' =~ /(?[ \p{SB=UP} + [_] ])/i,
        'extended-class union retains no-fold property membership');
    ok('a' !~ /(?[ \p{SB=UP} + [_] ])/i,
        'extended-class union does not fold a no-fold property');
    ok('A' =~ /(?[ \p{SB=UP} & [A-Z] ])/i,
        'extended-class intersection retains native property provenance');
    ok('a' !~ /(?[ \p{SB=UP} & [A-Z] ])/i,
        'extended intersection does not fold no-fold property membership');
    ok('A' !~ /(?[ [A-Z] - \p{SB=UP} ])/i,
        'extended-class subtraction removes native property members');
    ok('a' =~ /(?[ [A-Z] - \p{SB=UP} ])/i,
        'extended subtraction preserves folded literal-only member');
    ok('a' =~ /(?[ ! \p{SB=UP} ])/i && 'A' !~ /(?[ ! \p{SB=UP} ])/i,
        'extended unary complement retains no-fold property policy');
    ok("\x{0301}" =~ /(?[ \p{ccc=:\AAbove\z:} + [_] ])/i,
        'no-fold wildcard value resolves natively in an extended class');
    ok('A' =~ /(?[ \p{SB=:\AUpper\z:} + [B] ])/i
            && 'a' !~ /(?[ \p{SB=:\AUpper\z:} + [B] ])/i
            && 'b' =~ /(?[ \p{SB=:\AUpper\z:} + [B] ])/i,
        'sentence wildcard retains mixed policy in an extended class');
}

our ($deferred_class, $extended_collision_error, $callback_fold);
BEGIN {
    $deferred_class = qr/[\p{InGreek}_]/;
    {
        no warnings 'experimental::regex_sets';
        eval q{qr/(?[ \p{InGreek} + [_] ])/};
        $extended_collision_error = $@;
    }
    $callback_fold = qr/[\p{IsNativeClassFold}_]/i;
}
sub InGreek { "0600\n" }
sub IsNativeClassFold { "0041\n" }
ok("\x{0600}" =~ $deferred_class && "\x{0370}" !~ $deferred_class,
    'deferred callback outranks colliding built-in in a standard class');
like($extended_collision_error, qr/Unknown user-defined property name "InGreek"/,
    'forward extended-class collision stays fatal under warn policy');
ok('A' =~ $callback_fold && 'a' !~ $callback_fold,
    'case-insensitive callback result retains callback-owned fold policy');
ok('a' =~ /[\P{IsNativeClassFold}]/i && 'A' !~ /[\P{IsNativeClassFold}]/i,
    'case-insensitive callback negation retains callback-owned policy');

done_testing;
