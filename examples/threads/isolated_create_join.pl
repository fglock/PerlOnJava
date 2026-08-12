#!/usr/bin/env perl
use strict;
use warnings;

use threads;

my $parent_value = 10;
my $worker = threads->create(sub {
    my ($increment) = @_;
    $parent_value += $increment;
    return {
        tid   => threads->self->tid,
        value => $parent_value,
    };
}, 7);

my $result = $worker->join;
die "child did not run in an independent ithread\n"
    unless $result->{tid} > 0 && $result->{value} == 17;
die "child mutation escaped its runtime snapshot\n"
    unless $parent_value == 10;

print "child $result->{tid} returned $result->{value}; parent remains $parent_value\n";
