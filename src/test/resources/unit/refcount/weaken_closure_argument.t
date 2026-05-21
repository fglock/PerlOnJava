#!/usr/bin/env perl
use strict;
use warnings;

use Scalar::Util qw(isweak weaken);
use Test::More tests => 3;

sub run_callback {
    my $callback = shift;
    return weaken_and_call($callback);
}

sub weaken_and_call {
    weaken(my $weak_callback = shift);

    ok(isweak($weak_callback), 'closure argument copy is weak');
    ok(defined $weak_callback, 'weak closure argument copy survives while caller holds it');
    is($weak_callback->(), 'captured value', 'weak closure argument remains callable');
}

my $captured = 'captured value';
run_callback(sub { $captured });
