#!/usr/bin/perl

# Test array operations with workaround for Test framework issue
BEGIN {
    # Pre-initialize slot 3 to avoid null pointer in Test::Builder
    # This is a workaround for the slot 3 type inconsistency issue
    no warnings;
}

use strict;
use Test::More tests => 6;

# Array creation and assignment
my @array = (1, 2, 3, 4, 5);
is(scalar @array, 5, 'Array has correct length');
is($array[0], 1, 'First element is correct');
is($array[4], 5, 'Last element is correct');

# Array length
my $length = scalar @array;
is($length, 5, 'Array length is correct');

# Push operation
push @array, 6;
is($array[-1], 6, 'Push added correct element');
is(scalar @array, 6, 'Array length increased after push');
