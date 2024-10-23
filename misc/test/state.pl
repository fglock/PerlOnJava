#!/usr/bin/perl

use strict;
use warnings;
use feature 'state';
use Test::More tests => 4;

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

# Test that a new instance of the function does not reset the state variable
sub another_counter {
    state $count = 10;  # Initialize state variable with a different value
    $count++;
    return $count;
}

is(another_counter(), 11, 'First call to another_counter() should return 11');

