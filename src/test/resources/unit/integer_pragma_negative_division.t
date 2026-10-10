use strict;
use warnings;
use Test::More tests => 4;

{
    use integer;

    is(-1 / 1_000_000_000, 0, 'integer division truncates a negative fraction toward zero');
    is(1 + -1 / 1_000_000_000, 1, 'negative fraction keeps the one-second normalization carry');

    my ($seconds, $nanoseconds) = (0, -1);
    if ($nanoseconds < 0) {
        my $overflow = 1 + $nanoseconds / 1_000_000_000;
        $nanoseconds += $overflow * 1_000_000_000;
        $seconds -= $overflow;
    }

    is($seconds, -1, 'subtracting one nanosecond borrows one second');
    is($nanoseconds, 999_999_999, 'negative nanoseconds normalize into the previous second');
}
