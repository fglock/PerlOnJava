use strict;
use warnings;
use Test::More;
no warnings 'experimental::uniprop_wildcards';

my $specialized_probe = eval q{qr/\p{kEH_NoRotate=:\A(?:Y|Yes)\z:}/};
if (!defined $specialized_probe) {
    plan skip_all => 'pinned specialized binary properties await resolver integration';
}

my @properties = (
    ['Hyphen',                  0xFF65, 0xFF66],
    ['kEH_NoMirror',            0x13081, 0x13082],
    ['kEH_NoRotate',            0x143E8, 0x143E9],
    ['ID_Compat_Math_Continue', 0x00B2, 0x00B1],
    ['ID_Compat_Math_Start',    0x2202, 0x2203],
    ['IDS_Unary_Operator',      0x2FFF, 0x3000],
    ['Modifier_Combining_Mark', 0x0654, 0x0653],
);
for my $property (@properties) {
    my ($name, $positive, $negative) = @$property;
    my $bare;
    {
        local $SIG{__WARN__} = sub {};
        $bare = eval "qr/\\p{$name}/";
    }
    ok(defined $bare, "$name bare form compiles") or diag($@);
    like(chr($positive), $bare, "$name bare form matches positive code point");
    unlike(chr($negative), $bare, "$name bare form rejects adjacent negative code point");
    my $yes;
    {
        local $SIG{__WARN__} = sub {} if $name eq 'Hyphen';
        $yes = eval "qr/\\p{$name=Yes}/";
    }
    ok(defined $yes, "$name Yes form compiles") or diag($@);
    like(chr($positive), $yes, "$name Yes form matches positive code point");
    my $no;
    {
        local $SIG{__WARN__} = sub {} if $name eq 'Hyphen';
        $no = eval "qr/\\p{$name=No}/";
    }
    ok(defined $no, "$name No form compiles") or diag($@);
    like(chr($negative), $no, "$name No form matches negative code point");
}

for my $alias (
    [q{qr/\p{IDSU}/}, 0x2FFE, 'IDSU short alias'],
    [q{qr/\p{MCM}/}, 0x0658, 'MCM short alias'],
    [q{qr/\p{k e h-no mirror}/}, 0x13084, 'loose kEH_NoMirror alias'],
    [q{qr/\p{id compat math start}/}, 0x2207, 'loose ID_Compat_Math_Start alias'],
) {
    my ($source, $code_point, $description) = @$alias;
    my $pattern = eval $source;
    ok(defined $pattern, "$description compiles") or diag($@);
    like(chr($code_point), $pattern, "$description matches");
}

my @hyphen_warnings;
{
    local $SIG{__WARN__} = sub { push @hyphen_warnings, @_ };
    my $hyphen = eval q{qr/\p{Hyphen}/};
    ok(defined $hyphen, 'deprecated Hyphen remains accepted');
}
like(join('', @hyphen_warnings), qr/deprecated.*Supplanted by Line_Break/s,
    'Hyphen emits the pinned Perl deprecation warning');

my @accepted_wildcards = (
    [q{qr/\p{kEH_NoRotate=:\A(?:Y|Yes)\z:}/}, 0x143E8, 'kEH wildcard'],
    [q{qr/\p{IDSU=:\A(?:Y|T|True)\z:}/}, 0x2FFF, 'IDSU wildcard'],
    [q{qr/\p{MCM=:\A(?:N|No|False)\z:}/}, 0x0653, 'MCM negative wildcard'],
);
for my $wildcard (@accepted_wildcards) {
    my ($source, $code_point, $description) = @$wildcard;
    my $pattern = eval $source;
    ok(defined $pattern, "$description compiles") or diag($@);
    like(chr($code_point), $pattern, "$description matches");
}

my @rejected = (
    [q{qr/\p{kEH_Cat}/}, qr/Can't find Unicode property definition/,
        'non-binary kEH catalog property without a value'],
    [q{qr/\p{Hyphen=Maybe}/}, qr/Can't find Unicode property definition/,
        'unknown binary value'],
    [q{qr/\p{Is_IDSU=:\AYes\z:}/}, qr/Can't find Unicode property definition/,
        'Is-prefixed wildcard'],
    [q{qr/\p{MCM=:\A.*\z:}/}, qr/quantifier '\*' is not allowed/i,
        'star-quantifier wildcard'],
);
for my $rejected (@rejected) {
    my ($source, $error, $description) = @$rejected;
    my $pattern = eval $source;
    ok(!defined $pattern, "$description is rejected");
    like($@, $error, "$description reports the expected diagnostic");
}

done_testing();
