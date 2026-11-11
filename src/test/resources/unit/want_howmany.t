#!/usr/bin/env perl
use strict;
use warnings;
use Test::More tests => 2;

use Want qw(howmany);

sub count_in_caller_context { return howmany() }

is(scalar count_in_caller_context(), 1, 'scalar context expects one value');
my @values = count_in_caller_context();
ok(!defined($values[0]), 'unconstrained list context has no fixed item count');
