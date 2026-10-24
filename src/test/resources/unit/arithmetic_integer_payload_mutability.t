use strict;
use warnings;
use Test::More tests => 6;

my ($left, $right) = (5_000 + 7_000, 5_000 + 7_000);
is($left, 12_000, 'integer arithmetic keeps the expected value');
ok(!Internals::SvREADONLY($left), 'arithmetic results remain writable Perl scalars');

my $left_ref = \$left;
$$left_ref = 12_001;
is($left, 12_001, 'a cached integer payload can be changed through its scalar');
is($right, 12_000, 'changing one scalar does not change another scalar with the same value');

$right++;
is($right, 12_001, 'incrementing the second scalar updates its own value');
is($left, 12_001, 'incrementing one scalar leaves the other scalar unchanged');
