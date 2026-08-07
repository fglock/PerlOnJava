#!/usr/bin/env perl

use strict;
use warnings;
use Test::More tests => 4;

eval q{
    package EvalDefinedCaller;

    sub inner {
        my @names;
        for my $depth (0 .. 3) {
            push @names, scalar((caller($depth))[3]);
        }
        return \@names;
    }

    sub middle { inner() }
    sub outer  { &middle }
};
die $@ if $@;

package main;

my $callers = eval { EvalDefinedCaller::outer('inherited argument') };
die $@ if $@;

is($callers->[0], 'EvalDefinedCaller::inner',
    'caller zero names an eval-defined interpreted subroutine');
is($callers->[1], 'EvalDefinedCaller::middle',
    'caller one names its eval-defined caller');
is($callers->[2], 'EvalDefinedCaller::outer',
    'caller before virtual eval keeps the outer named frame');
is($callers->[3], '(eval)',
    'caller after eval-defined subroutines keeps the eval frame');
