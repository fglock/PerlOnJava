use strict;
use warnings;
use Test::More;
use FindBin;
use lib "$FindBin::Bin/fixtures";
use Set::Scalar 1.29;

my ($a, $b, $c, $d) = qw(a b c d);
# This is the first De Morgan property checked by Set::Scalar 1.29's t/laws.t.
# The third set extends the shared universe through g before complements run.
my $x = Set::Scalar->new(qw(a b c));
my $y = Set::Scalar->new(qw(c d e));
my $z = Set::Scalar->new(qw(e f g));
my $null = $x->null;
my $universe = $x->universe;
my $double_complement = -(-$x);
is($double_complement == $x, 1, 'double complement retains the original set');
sub issue_2074_set_equal {
    my ($left, $right) = @_;
    return $left == $right
        || ($left->size == 0 && $left->size == $right->size);
}
my $left = -($x + $x);
my $right = -$x * -$x;
is($left == $right, 1,
    'Set::Scalar operations produce equal results when retained in lexical slots');
is(issue_2074_set_equal($left, $right), 1,
    'Set::Scalar results remain equal when passed to a helper after evaluation');
is(issue_2074_set_equal(-($x + $x), -$x * -$x), 1,
    'overloaded results compare correctly when passed directly as helper arguments');
is_deeply([sort $left->members], [qw(d e f g)], 'complement of a union has expected members');
is_deeply([sort $right->members], [qw(d e f g)], 'intersection of complements has expected members');

# Keep this after the set law checks: the indirect constructor's scalar
# references become elements in Set::Scalar's shared universe.
my $parsed = eval q{
    new Set::Scalar \$a, \$b, \$c,->intersection(
        new Set::Scalar \$b, \$c, \$d
    )
};
is($@, '', 'indirect constructor followed by an intersection method call parses');
SKIP: {
    skip 'the indirect constructor expression did not parse', 2 if $@;
    isa_ok($parsed, 'Set::Scalar', 'indirect intersection returns a set');
    is($parsed->size, 2, 'indirect intersection contains both common members');
}

done_testing;
