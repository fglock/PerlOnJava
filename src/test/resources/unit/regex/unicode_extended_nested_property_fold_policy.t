use strict;
use warnings;
no warnings 'experimental::regex_sets';
use Test::More;

my $mixed = qr/(?[ [\p{SB=UP}B] ])/i;
ok('A' =~ $mixed, 'nested standard leaf retains no-fold property member');
ok('a' !~ $mixed, 'nested standard leaf does not fold property-only member');
ok('B' =~ $mixed, 'nested standard leaf retains literal member');
ok('b' =~ $mixed, 'literal beside no-fold property still folds');

my $intersection = qr/(?[ [\p{SB=UP}B] & [AaBb] ])/i;
ok('A' =~ $intersection, 'intersection retains no-fold property member');
ok('a' !~ $intersection, 'intersection does not fold property-only member');
ok('b' =~ $intersection, 'intersection retains folded literal provenance');

my $subtraction = qr/(?[ [\p{SB=UP}B] - [A] ])/i;
ok('A' !~ $subtraction, 'subtraction removes its folded right operand');
ok('a' !~ $subtraction, 'subtraction does not synthesize lowercase property member');
ok('b' =~ $subtraction, 'subtraction preserves unrelated folded literal member');

our @callback_modes;
sub IsNestedPolicy {
    push @callback_modes, $_[0] ? 1 : 0;
    return "0041\n";
}

my $callback = eval q{qr/(?[ [\p{IsNestedPolicy}B] ])/i};
is($@, '', 'nested callback-owned property leaf compiles');
ok('A' =~ $callback && 'a' !~ $callback,
    'callback-owned member is not automatically folded');
ok('B' =~ $callback && 'b' =~ $callback,
    'literal beside callback-owned member keeps folding');
is_deeply(\@callback_modes, [1],
    'callback-owned leaf resolves once in its local fold mode');

done_testing;
