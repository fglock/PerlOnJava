#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;
use Time::Local ();

sub tz_offset {
    my ($time) = @_;
    my @local = localtime $time;
    my @gmt   = gmtime $time;
    $local[5] += 1900;
    $gmt[5]   += 1900;
    my $diff = Time::Local::timegm_modern(@local)
             - Time::Local::timegm_modern(@gmt);
    my $sign = $diff < 0 ? '-' : '+';
    $diff = abs $diff;
    return sprintf '%s%02d%02d', $sign, int($diff / 3600), int($diff / 60) % 60;
}

SKIP: {
    skip 'TZ offset strings are not portable on Windows', 2 if $^O eq 'MSWin32';

    local $ENV{TZ} = 'UTC-11';
    is(tz_offset(1153432704), '+1100', 'localtime honors positive POSIX TZ offset');

    $ENV{TZ} = 'UTC+9';
    is(tz_offset(1153432704), '-0900', 'localtime honors negative POSIX TZ offset');
}

done_testing;
