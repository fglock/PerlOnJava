#!/usr/bin/env perl

use strict;
use warnings;
use Scalar::Util qw(weaken);
use Test::More tests => 3;

our @timer_queue;
our $hit = 0;

sub make_weak_queued_callback {
    my ($cb) = @_;
    my $watcher;

    $watcher = [ sub { $cb->() } ];
    push @timer_queue, $watcher;
    weaken $timer_queue[-1];

    return $watcher;
}

sub install_callback {
    my $watcher;

    $watcher = make_weak_queued_callback(sub {
        $hit++;
        $watcher;
    });

    return 1;
}

install_callback();

ok(defined $timer_queue[0], 'weak queue entry survives through self-retaining closure cycle');
$timer_queue[0][0]->();
is($hit, 1, 'callback in self-retaining weak queue remains callable');
ok(defined $timer_queue[0], 'weak queue entry remains defined after callback runs');
