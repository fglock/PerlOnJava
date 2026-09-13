#!/usr/bin/perl

use strict;
use Test::More tests => 1;
use feature ':5.10';

sub stateful {
    state $scalar = 0;
    ++$scalar;
    return $scalar;
}

stateful() for 1 .. 3;

sub make_counter {
    my $offset = shift;
    return sub {
        state $count = 0;
        ++$count + $offset;
    };
}

my $counter = make_counter(10);
$counter->();
$counter->();

my @simpsons = qw(Homer Marge Bart Lisa Maggie);
my @seen;
again:
my $next = shift @simpsons;
state $simpson = $next;
push @seen, $simpson;
goto again if @simpsons;
is_deeply(\@seen, [qw(Homer Homer Homer Homer Homer)],
    'state initializer runs once across goto after closures');
