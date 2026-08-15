use strict;
use warnings;
use Test::More tests => 2;

use Time::HiRes qw(ualarm);

ok(defined &ualarm, 'ualarm is exported on request');
cmp_ok(ualarm(0), '>=', 0, 'ualarm can cancel a timer');
