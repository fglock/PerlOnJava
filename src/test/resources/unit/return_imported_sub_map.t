use Test::More;
use List::Util qw(sum);

sub sum_map_return {
    return sum map { $_ } (1, 2);
}

is(sum_map_return(), 3, 'return accepts imported subroutine followed by map');

done_testing;
