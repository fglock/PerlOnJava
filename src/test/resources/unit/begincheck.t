#!/usr/bin/perl
use strict;
use warnings;
use Test::More tests => 16;

my $test_counter;
BEGIN { $test_counter = 1; }

# Runtime tests
is($test_counter, 10, 'Ordinary code runs at runtime');
$test_counter++;

END {
    is($test_counter, 16, 'So this is the end of the tale');
    $test_counter++;
}

INIT {
    is($test_counter, 7, 'INIT blocks run FIFO just before runtime');
    $test_counter++;
}

UNITCHECK {
    is($test_counter, 4, 'And therefore before any CHECK blocks');
    $test_counter++;
}

CHECK {
    is($test_counter, 6, 'So this is the sixth line');
    $test_counter++;
}

is($test_counter, 11, 'It runs in order, of course');
$test_counter++;

BEGIN {
    is($test_counter, 1, 'BEGIN blocks run FIFO during compilation');
    $test_counter++;
}

END {
    is($test_counter, 15, 'Read perlmod for the rest of the story');
    $test_counter++;
}

CHECK {
    is($test_counter, 5, 'CHECK blocks run LIFO after all compilation');
    $test_counter++;
}

INIT {
    is($test_counter, 8, "Run this again, using Perl's -c switch");
    $test_counter++;
}

is($test_counter, 12, 'This is anti-obfuscated code');
$test_counter++;

END {
    is($test_counter, 14, 'END blocks run LIFO at quitting time');
    $test_counter++;
}

BEGIN {
    is($test_counter, 2, 'So this line comes out second');
    $test_counter++;
}

UNITCHECK {
    is($test_counter, 3, 'UNITCHECK blocks run LIFO after each file is compiled');
    $test_counter++;
}

INIT {
    is($test_counter, 9, "You'll see the difference right away");
    $test_counter++;
}

is($test_counter, 13, 'It only _looks_ like it should be confusing');
$test_counter++;
