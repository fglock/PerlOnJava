#!/usr/bin/perl

# Complex array test without Test framework
print "1..10\n";

# Array creation and assignment
my @array = (1, 2, 3, 4, 5);
print "ok 1 - Array has correct length\n" if scalar @array == 5;

# Push operation
push @array, 6;
print "ok 2 - Push added correct element\n" if $array[-1] == 6;

# Pop operation
my $popped = pop @array;
print "ok 3 - Pop returned correct element\n" if $popped == 6;
print "ok 4 - Array length decreased after pop\n" if scalar @array == 5;

# Shift operation
my $shifted = shift @array;
print "ok 5 - Shift returned correct element\n" if $shifted == 1;
print "ok 6 - Array length decreased after shift\n" if scalar @array == 4;

# Unshift operation
unshift @array, 0;
print "ok 7 - Unshift added element at beginning\n" if $array[0] == 0;
print "ok 8 - Array length increased after unshift\n" if scalar @array == 5;

# Splice operation
splice @array, 2, 1, (10, 11);
print "ok 9 - Array length correct after splice\n" if scalar @array == 6;
print "ok 10 - Splice inserted element correctly\n" if $array[2] == 10;
