#!/usr/bin/perl

use strict;
use warnings;
use Test::More tests => 9;
use feature 'state';

# Function using a state variable
sub counter {
    state $count = 0;  # Initialize state variable
    $count++;
    return $count;
}

# Test that the state variable retains its value across calls
is(counter(), 1, 'First call to counter() should return 1');
is(counter(), 2, 'Second call to counter() should return 2');
is(counter(), 3, 'Third call to counter() should return 3');

# Function to create a new counter using an anonymous subroutine
sub create_counter {
    return sub {
        state $x = 0;  # Each instance has its own state variable
        return ++$x;
    };
}

# Create two separate counters
my $counter1 = create_counter();
my $counter2 = create_counter();

# Test that each counter maintains its own state
is($counter1->(), 1, 'First call to counter1 should return 1');
is($counter1->(), 2, 'Second call to counter1 should return 2');
is($counter2->(), 1, 'First call to counter2 should return 1');
is($counter2->(), 2, 'Second call to counter2 should return 2');

# Test that state variable initialization only occurs once
sub single_init {
    state $y = 42;
    return $y++;
}

is(single_init(), 42, 'First call to single_init should return 42');
is(single_init(), 43, 'Second call to single_init should return 43');

done_testing();

