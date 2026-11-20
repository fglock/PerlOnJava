#!/usr/bin/env perl
use strict;
use warnings;

# Test 1: Simple C-style for loop
print "Test 1: C-style for loop\n";
my $sum = 0;
for (my $i = 0; $i < 10; $i++) {
    $sum = $sum + $i;
}
print "Sum 0-9: $sum\n";  # Expected: 45

# Test 2: Foreach-style loop with range
print "\nTest 2: Foreach with range\n";
my $total = 0;
for my $n (1..5) {
    $total = $total + $n;
}
print "Sum 1-5: $total\n";  # Expected: 15

# Test 3: Foreach with explicit list
print "\nTest 3: Foreach with list\n";
my $result = 0;
for my $x (2, 4, 6) {
    $result = $result + $x;
}
print "Sum of 2,4,6: $result\n";  # Expected: 12
