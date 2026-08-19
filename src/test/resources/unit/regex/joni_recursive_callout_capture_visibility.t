use strict;
use warnings;
use Test::More tests => 10;

our @stack;
local $_ = '<<<stuff1>and<stuff2>><<<<right>>>>>';

ok /^(<((?:(?>[^<>]+)|(?1))*)>(?{push @stack, $2 }))$/,
    'recursive pattern with capture-observing callback matches';

my @expected = (
    'stuff1',
    'stuff2',
    '<stuff1>and<stuff2>',
    'right',
    '<right>',
    '<<right>>',
    '<<<right>>>',
    '<<stuff1>and<stuff2>><<<<right>>>>',
);

is scalar(@stack), scalar(@expected),
    'callback executes once for each completed recursion level';
for my $index (0 .. $#expected) {
    is $stack[$index], $expected[$index],
        "callback sees recursion-local capture at stack position $index";
}
