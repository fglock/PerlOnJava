use strict;
use warnings;
use Test::More tests => 3;
use Time::HiRes qw(clock_gettime CLOCK_MONOTONIC CLOCK_REALTIME);

my $first = clock_gettime(CLOCK_MONOTONIC);
my $second = clock_gettime(CLOCK_MONOTONIC);
ok($second >= $first, 'monotonic clock readings do not go backwards');

my $realtime = clock_gettime(CLOCK_REALTIME);
ok($realtime > 1_000_000_000, 'realtime clock returns epoch seconds');
ok(CLOCK_MONOTONIC != CLOCK_REALTIME, 'clock identifiers are distinct');
