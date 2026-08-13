#!/usr/bin/env perl
use strict;
use warnings;
use Test::More tests => 4;
use Config ();

use POSIX ();

ok(defined &POSIX::DBL_EPSILON,
    'POSIX::DBL_EPSILON is available without importing it');
ok(defined &POSIX::LDBL_EPSILON,
    'POSIX::LDBL_EPSILON is available without importing it');

my $epsilon = $Config::Config{uselongdouble}
    ? POSIX::LDBL_EPSILON
    : POSIX::DBL_EPSILON;

ok($epsilon > 0, 'the configured floating-point epsilon is positive');
ok(1 + $epsilon > 1, 'the configured epsilon changes one at machine precision');
