#!/usr/bin/env perl
use strict;
use warnings;
use Test::More tests => 6;

use POSIX qw(:float_h);

ok(defined(&DBL_MAX), 'DBL_MAX is imported from :float_h');
ok(defined(&DBL_MIN), 'DBL_MIN is imported from :float_h');
ok(defined(&DBL_EPSILON), 'DBL_EPSILON is imported from :float_h');
is(FLT_RADIX(), 2, 'FLT_RADIX matches the binary floating-point radix');
cmp_ok(DBL_MAX(), '>', DBL_MIN(), 'double maximum exceeds double minimum');
cmp_ok(DBL_EPSILON(), '>', 0, 'double epsilon is positive');
