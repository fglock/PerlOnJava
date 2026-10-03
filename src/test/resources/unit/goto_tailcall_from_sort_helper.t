use strict;
use warnings;
use Test::More;

sub compare_values { $a <=> $b }
sub compare_from_helper {
    goto &compare_values;
}

my @sorted = sort { compare_from_helper() } (3, 1, 2);
is_deeply(\@sorted, [1, 2, 3],
    'named helper may tail-call from a sort comparator');

done_testing;
