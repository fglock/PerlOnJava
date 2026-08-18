use strict;
use warnings;
use Test::More;
no warnings 'experimental::uniprop_wildcards';

my $joining_group_probe = eval q{qr/\p{jg=No_Joining_Group}/};
if (!defined $joining_group_probe) {
    plan skip_all => 'pinned Joining_Group data awaits resolver integration';
}

my @values = (
    ['African_Feh',          'African_Feh',          0x08BB],
    ['Ain',                  'Ain',                  0x0639],
    ['Alef',                 'Alef',                 0x0622],
    ['Manichaean_Aleph',     'Manichaean_Aleph',     0x10AC0],
    ['No_Joining_Group',     'No_Joining_Group',     0x0000],
    ['Teh_Marbuta_Goal',    'Teh_Marbuta_Goal',    0x06C3],
);

for my $value (@values) {
    my ($short, $long, $code_point) = @$value;
    my $character = chr($code_point);
    my $short_pattern = eval "qr/\\p{jg=$short}/";
    ok(defined $short_pattern, "short value $short compiles")
        or diag($@);
    like($character, $short_pattern, "short value $short matches representative");

    my $long_pattern = eval "qr/\\p{Joining_Group=$long}/";
    ok(defined $long_pattern, "long value $long compiles")
        or diag($@);
    like($character, $long_pattern, "long value $long matches representative");
}

SKIP: {
    my $short_pattern = eval q{qr/\p{jg=Thin_Noon}/};
    skip 'Thin_Noon requires Perl 5.44 Unicode 17 data', 4
        unless defined $short_pattern;
    ok(defined $short_pattern, 'Unicode 17 short value Thin_Noon compiles');
    like(chr(0x10EC6), $short_pattern,
        'Unicode 17 short value Thin_Noon matches representative');
    my $long_pattern = eval q{qr/\p{Joining_Group=Thin_Noon}/};
    ok(defined $long_pattern, 'Unicode 17 long value Thin_Noon compiles');
    like(chr(0x10EC6), $long_pattern,
        'Unicode 17 long value Thin_Noon matches representative');
}

my $loose = eval q{qr/\p{joining group = african feh}/};
ok(defined $loose, 'loose property and value aliases compile') or diag($@);
like(chr(0x08BB), $loose, 'loose property and value aliases match');

my $alternate = eval q{qr/\p{jg=Hamza_On_Heh_Goal}/};
ok(defined $alternate, 'alternate value alias compiles') or diag($@);
like(chr(0x06C3), $alternate, 'alternate value alias matches canonical set');

my $no_joining_group = eval q{qr/\p{jg=No_Joining_Group}/};
like(chr(0x0378), $no_joining_group,
    'unassigned code point uses No_Joining_Group default');
like(chr(0x10FFFF), $no_joining_group,
    'final code point uses No_Joining_Group default');
unlike(chr(0x0639), $no_joining_group,
    'explicit Ain range overrides No_Joining_Group default');

my @accepted_wildcards = (
    [q{qr/\p{jg=:\AAfrican_.+\z:}/}, 0x08BB, 'canonical wildcard'],
    [q{qr/\p{jg=:\Aafrican.+\z:}/},  0x08BB, 'squeezed wildcard'],
    [q{qr/\p{jg=:\A(?:Ain|Alef)\z:}/}, 0x0639, 'selected wildcard'],
    [q{qr/\p{jg=:\AHamza_On_Heh_Goal\z:}/}, 0x06C3,
        'alternate-alias wildcard'],
);
for my $wildcard (@accepted_wildcards) {
    my ($source, $code_point, $description) = @$wildcard;
    my $pattern = eval $source;
    ok(defined $pattern, "$description compiles") or diag($@);
    like(chr($code_point), $pattern, "$description matches");
}

my @rejected_wildcards = (
    [q{qr/\p{jg=:\ANot_A_Value\z:}/},
        qr/No Unicode property value wildcard matches/, 'unmatched wildcard'],
    [q{qr/\p{Is_jg=:\AAin\z:}/},
        qr/Can't find Unicode property definition/, 'Is-prefixed wildcard'],
    [q{qr/\p{jg=:\A.*\z:}/},
        qr/quantifier '\*' is not allowed/i, 'star-quantifier wildcard'],
);
for my $wildcard (@rejected_wildcards) {
    my ($source, $error_pattern, $description) = @$wildcard;
    my $pattern = eval $source;
    ok(!defined $pattern, "$description is rejected");
    like($@, $error_pattern, "$description reports the expected diagnostic");
}

done_testing();
