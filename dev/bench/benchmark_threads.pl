#!/usr/bin/env perl
use strict;
use warnings;
use Time::HiRes qw(time);
use threads;

my $iterations = shift // 10;
die "iterations must be a positive integer\n"
    unless $iterations =~ /^\d+$/ && $iterations > 0;

my $start = time;
my $checksum = 0;
for my $batch (1 .. $iterations) {
    my @workers = map {
        my $value = $_;
        threads->create(sub { $value + 1 });
    } 1 .. 10;
    $checksum += $_->join for @workers;
}
my $elapsed = time - $start;

printf "threads=%d elapsed=%.6f checksum=%d\n",
    $iterations * 10, $elapsed, $checksum;
