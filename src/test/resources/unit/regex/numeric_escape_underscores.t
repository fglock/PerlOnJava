use strict;
use warnings;
use utf8;
use Test::More;

my $e_acute = "\x{e9}";
ok($e_acute =~ /\xE9/, 'unbraced hex escape matches a UTF-8 scalar');
ok($e_acute =~ /[\xE9]/, 'unbraced hex escape works in a character class');

my $sharp_s = "\x{df}";
ok($sharp_s =~ /\337/, 'unbraced high octal escape matches a UTF-8 scalar');
ok($sharp_s =~ /[\337]/,
    'unbraced high octal escape works in a character class');

my $smile = "\x{1f642}";
ok($smile =~ /\x{1_F642}/,
    'underscored braced hex escape matches a supplementary scalar');
ok($smile =~ /[\x{1_F642}]/,
    'underscored braced hex escape works in a character class');
ok($smile =~ /\o{373_102}/,
    'underscored braced octal escape matches a supplementary scalar');
ok($smile =~ /[\o{373_102}]/,
    'underscored braced octal escape works in a character class');

my $unicode_max = chr(0x10ffff);
ok($unicode_max =~ /\x{10_FFFF}/,
    'underscored hex accepts the maximum Unicode code point');
ok($unicode_max =~ /\o{4_177_777}/,
    'underscored octal accepts the maximum Unicode code point');
ok('A' =~ /\x{0_0000_0041}/,
    'leading zeroes do not count toward the braced hex digit limit');

for my $case (
    [ q!qr/\x{41/!,  qr/Missing right brace on \\x\{\}/,
        'missing hex right brace is a structural error' ],
    [ q!qr/\o{101/!, qr/Missing right brace on \\o\{\}/,
        'missing octal right brace is a structural error' ],
    [ q!qr/\o{}/!,   qr/Empty \\o\{\}/,
        'empty octal escape is a structural error' ],
) {
    my ($source, $error, $name) = @$case;
    my $compiled = eval $source;
    ok(!defined($compiled) && $@ =~ $error, $name);
}

TODO: {
    local $TODO = q{Perl use re 'strict' numeric-escape diagnostics need source policy};
    local $SIG{__WARN__} = sub { };

    my $hex = eval q!use re 'strict'; qr/\x{4__1}/!;
    ok(!defined($hex) && $@ =~ /Non-hex character/,
        'strict mode rejects adjacent hex underscores');

    my $octal = eval q!use re 'strict'; qr/\o{1__01}/!;
    ok(!defined($octal) && $@ =~ /Non-octal character/,
        'strict mode rejects adjacent octal underscores');
}

TODO: {
    local $TODO = 'Joni cannot represent Perl scalars above U+10FFFF';
    no warnings 'utf8';

    my $above_unicode = chr(0x110000);
    my $hex = eval q{qr/\x{11_0000}/};
    ok($hex && $above_unicode =~ $hex,
        'underscored hex preserves a scalar above Unicode');
    my $octal = eval q{qr/\o{4_200_000}/};
    ok($octal && $above_unicode =~ $octal,
        'underscored octal preserves a scalar above Unicode');
}

done_testing;
