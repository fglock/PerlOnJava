use strict;
use warnings;
use Test::More;

my $microseconds = 1051470597;
$microseconds *= 1e6;
$microseconds += int(0.8 * 1e6);

is("$microseconds", '1051470597800000',
    'large integral arithmetic with int() stringifies as decimal');

like("" . (1.5 * 1_000_000_000_000_000), qr/[eE+]/,
    'large fractional NV values retain scientific notation');

like("" . 1e20, qr/[eE+]/,
    'large floating literals retain scientific notation');

done_testing;
