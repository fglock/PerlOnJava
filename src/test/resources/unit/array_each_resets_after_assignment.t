#!/usr/bin/env perl

use strict;
use warnings;
use Test::More tests => 2;

my @items = 'a' .. 'c';
my ($index, $value) = each @items;
is("$index-$value", '0-a', 'each starts at the first array element');

@items = 'A' .. 'C';
($index, $value) = each @items;
is("$index-$value", '0-A', 'array replacement resets the each iterator');
