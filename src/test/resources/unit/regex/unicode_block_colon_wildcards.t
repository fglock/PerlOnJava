use strict;
use warnings;
use Test::More;

no warnings 'experimental::uniprop_wildcards';

sub compile_property {
    my ($property) = @_;
    my $pattern = eval 'qr/\A\p{' . $property . '}\z/u';
    return ($pattern, $@);
}

my ($basic, $basic_error) = compile_property('Block=:\ABasic_Latin\z:');
ok(defined $basic, 'colon-delimited Block wildcard compiles')
    or diag($basic_error);
like('A', $basic, 'colon-delimited Block wildcard matches its block');
unlike(chr(0x03B1), $basic,
    'colon-delimited Block wildcard excludes another block');

my ($short, $short_error) = compile_property('Blk=:\AASCII\z:');
ok(defined $short, 'Blk accepts a colon-delimited wildcard alias')
    or diag($short_error);
like('A', $short, 'wildcard value accepts an official Block alias');

my ($loose, $loose_error) = compile_property('Block=:\Abasiclatin\z:');
ok(defined $loose, 'Block wildcard compares loose value spellings')
    or diag($loose_error);
like('A', $loose, 'loose wildcard value selects Basic_Latin');

my ($union, $union_error) =
    compile_property('Block=:\A(?:Basic_Latin|Greek_And_Coptic)\z:');
ok(defined $union, 'Block wildcard accepts value alternation')
    or diag($union_error);
like('A', $union, 'Block wildcard alternation includes Basic_Latin');
like(chr(0x0378), $union,
    'Block wildcard alternation includes Greek_And_Coptic');

for my $rejected (
    ['Block=:\ANever_A_Block\z:', 'wildcard matching no Block value'],
    ['Block=:\A.*\z:', 'star quantifier in Block wildcard'],
    ['Is_Block=:\ABasic_Latin\z:', 'Is-prefixed Block wildcard'],
) {
    my ($pattern, $error) = compile_property($rejected->[0]);
    ok(!defined($pattern) && length($error), "$rejected->[1] is rejected");
}

done_testing;
