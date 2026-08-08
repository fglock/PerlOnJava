use strict;
use warnings;
use Test::More tests => 2;

use_ok('Time::HiRes', 1.9764);
cmp_ok($Time::HiRes::VERSION, '>=', 1.9764,
    'Time::HiRes advertises the core-compatible version');
