#!/usr/bin/perl

# Simple array test without Test framework
print "1..6\n";

# Array creation and assignment
my @array = (1, 2, 3, 4, 5);
print "ok 1 - Array has correct length\n" if scalar @array == 5;
print "ok 2 - First element is correct\n" if $array[0] == 1;
print "ok 3 - Last element is correct\n" if $array[4] == 5;

# Array length
my $length = scalar @array;
print "ok 4 - Array length is correct\n" if $length == 5;

# Push operation
push @array, 6;
print "ok 5 - Push added correct element\n" if $array[-1] == 6;
print "ok 6 - Array length increased after push\n" if scalar @array == 6;
