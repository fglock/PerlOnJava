use strict;
use warnings;
use Test::More;

my $comparisons = 0;
sub dynamic_sort {
    return sort {
        $comparisons++;
        $a cmp $b;
    } @_;
}

my $scalar_result = dynamic_sort(qw(c b a));
ok(!defined($scalar_result), 'sort returned from a sub is undef in scalar context');
is($comparisons, 0, 'dynamic scalar sort does not invoke its comparator');

my @list_result = dynamic_sort(qw(c b a));
is_deeply(\@list_result, [qw(a b c)], 'sort returned from a sub preserves list context');
is($comparisons, 2, 'dynamic list sort invokes its comparator');

my $evaluations = 0;
sub dynamic_sort_with_input_side_effect {
    return sort {
        $comparisons++;
        $a <=> $b;
    } ($evaluations++, 3, 2);
}

my $side_effect_result = dynamic_sort_with_input_side_effect();
ok(!defined($side_effect_result), 'dynamic scalar sort still returns undef with an input expression');
is($evaluations, 1, 'dynamic scalar sort evaluates its input expression');
is($comparisons, 2, 'dynamic scalar sort skips the comparator for its input expression');

done_testing();
