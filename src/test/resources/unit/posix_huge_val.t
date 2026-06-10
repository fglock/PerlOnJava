#!/usr/bin/env perl
use strict;
use warnings;
use Test::More tests => 4;

use POSIX qw(HUGE_VAL);

ok(defined &HUGE_VAL, 'HUGE_VAL can be imported explicitly');
ok(HUGE_VAL() > 1e308, 'imported HUGE_VAL is numeric infinity');
ok(POSIX::HUGE_VAL() > 1e308, 'POSIX::HUGE_VAL is callable fully qualified');

{
    package POSIXHugeValDefault;
    POSIX->import;
    Test::More::ok(defined &HUGE_VAL, 'HUGE_VAL is exported by default');
}
