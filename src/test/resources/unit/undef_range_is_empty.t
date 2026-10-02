#!/usr/bin/env perl
use strict;
use warnings;
use Test::More tests => 2;

is(join(':', map "[$_]", undef .. undef), '[]',
    'undef .. undef yields an empty string in list context');

my @loop_values;
push @loop_values, $_ for undef .. undef;
is(join(':', map "[$_]", @loop_values), '[]',
    'undef .. undef yields an empty string in a for loop');
