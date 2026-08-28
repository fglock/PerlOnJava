use strict;
use warnings;
use Test::More tests => 5;

my @tree = (['text', "left\n"], ['line'], ['text', 'right']);
$tree[-3][1] .= pop(@tree)->[1];

is(scalar @tree, 2, 'compound assignment evaluates the mutating RHS once');
is($tree[0][1], "left\nright",
    'compound assignment resolves a negative-index lvalue before the RHS');

my @numbers = (10, 20, 30);
$numbers[-3] += pop @numbers;

is(scalar @numbers, 2, 'numeric compound assignment evaluates the mutating RHS once');
is($numbers[0], 40,
    'numeric compound assignment also resolves its lvalue before the RHS');

my %seen;
$seen{key} += scalar keys %seen;
is($seen{key}, 1, 'compound assignment vivifies its lvalue before the RHS');
