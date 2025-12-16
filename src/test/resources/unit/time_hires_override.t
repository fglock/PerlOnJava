#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;

# Test Time::HiRes import overrides the built-in time operator
# This is in a separate file to avoid conflicts with other operator override tests

plan tests => 4;

use Time::HiRes 'time';

my $t1 = time;
my $t2 = Time::HiRes::time;

ok($t1 =~ /\./, 'imported time returns fractional seconds');
ok($t2 =~ /\./, 'Time::HiRes::time returns fractional seconds');
ok(abs($t1 - $t2) < 0.1, 'imported time is close to Time::HiRes::time');

# Verify CORE::time still returns integer seconds
my $core_time = CORE::time;
ok($core_time !~ /\./, 'CORE::time returns integer seconds');

done_testing();
