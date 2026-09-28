use strict;
use warnings;
use Test::More;

sub range_or_flipflop {
    my ($from, $to) = @_;
    return $from .. $to;
}

is(scalar range_or_flipflop(0, 0), '', 'subroutine range is a scalar flip-flop');
is_deeply([range_or_flipflop(1, 3)], [1, 2, 3], 'subroutine range expands in list context');

done_testing;
