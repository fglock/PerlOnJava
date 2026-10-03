#!/usr/bin/env perl

use strict;
use warnings;
use Test::More tests => 2;

sub scalar_list_returns_undef { undef }
{
    no warnings 'void';
    ok(!defined(scalar(42, &scalar_list_returns_undef)),
       'scalar list expression returns its final value for defined');
}

my @items = qw(one two three);
is(scalar @items, 3, 'scalar array expression returns the element count');
