use strict;
use warnings;
use Test::More;

no warnings qw(experimental::regex_sets experimental::uniprop_wildcards);

sub compile_property {
    my ($property) = @_;
    my $regex = eval 'qr!\\A\\p{' . $property . '}\\z!u';
    return ($regex, $@);
}

my @cases = (
    ['sc=:\\A(?:Lat(in)?|Greek)\\z:', 'A', 'nested groups and alternation'],
    ['Block=#(?:Basic_)+Latin#', 'A', 'quantified group inside hash wildcard'],
    ['name=/(?i:katakana letter ne)/', chr(0x30CD), 'scoped case mode in Name'],
    ['nv=/\\A(?:1\\/2|3\\/2)\\z/', chr(0x00BD), 'escaped slash delimiter'],
    ['Age=:\\AV(?:1_1|2_0)\\z:', 'A', 'Age alternation'],
);

for my $case (@cases) {
    my ($regex, $error) = compile_property($case->[0]);
    ok(defined $regex, "$case->[2] compiles") or diag($error);
    like($case->[1], $regex, "$case->[2] selects its value") if defined $regex;
}

my $extended = eval 'qr!(?[ \\p{name=/KATAKANA/} ])!';
ok(defined $extended, 'Name wildcard compiles inside an extended class')
    or diag($@);
like(chr(0x30CD), $extended, 'extended class receives the selected Name set')
    if defined $extended;

done_testing;
