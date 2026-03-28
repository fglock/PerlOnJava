#!/usr/bin/env jperl
use strict;
use warnings;
use DateTime;

my $dt = DateTime->new(
    year      => 2026,
    month     => 3,
    day       => 28,
    time_zone => 'America/New_York'
);

print $dt->strftime('%Y-%m-%d %Z'), "\n";
