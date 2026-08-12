#!/usr/bin/env perl
use strict;
use warnings;

use threads;
use threads::shared;

my $state :shared = 0;

my $worker = threads->create(sub {
    lock($state);
    cond_wait($state) until $state == 1;
    $state = 2;
    cond_signal($state);
    return 'worker finished';
});

{
    lock($state);
    $state = 1;
    cond_signal($state);
    cond_wait($state) until $state == 2;
}

my $result = $worker->join;
die "shared condition workflow failed\n"
    unless $state == 2 && $result eq 'worker finished';

print "shared state reached $state after the worker signal\n";
