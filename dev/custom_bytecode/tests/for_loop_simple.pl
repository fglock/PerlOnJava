#!/usr/bin/env perl
use strict;
use warnings;

# Simple C-style for loop test
# This avoids complex features like say, print, etc.

my $sum = 0;
for (my $i = 0; $i < 10; $i++) {
    $sum = $sum + $i;
}

# Return value for verification
$sum;  # Should be 45
