#!/usr/bin/env perl

use strict;
use warnings;

print "1..3\n";

# Test 1: Dynamic goto with variable
{
    my $reached = 0;
    my $label = "TARGET";
    
    goto $label;
    $reached = 1;
    
    TARGET: $reached = 2;
    
    if ($reached == 2) {
        print "ok 1 - dynamic goto with variable\n";
    } else {
        print "not ok 1 - dynamic goto with variable (reached=$reached)\n";
    }
}

# Test 2: Dynamic goto with expression
{
    my $reached = 0;
    my $which = 1;
    
    goto ($which == 1 ? "LABEL1" : "LABEL2");
    $reached = 1;
    
    LABEL1: 
    $reached = 2;
    goto "END2";
    
    LABEL2: 
    $reached = 3;
    
    END2:
    if ($reached == 2) {
        print "ok 2 - dynamic goto with expression\n";
    } else {
        print "not ok 2 - dynamic goto with expression (reached=$reached)\n";
    }
}

# Test 3: Dynamic goto in loop
{
    my @results = ();
    my $target = "DONE";
    
    for my $i (1..5) {
        push @results, $i;
        goto $target if $i == 3;
    }
    
    DONE: 
    my $expected = join(',', 1, 2, 3);
    my $got = join(',', @results);
    
    if ($got eq $expected) {
        print "ok 3 - dynamic goto in loop\n";
    } else {
        print "not ok 3 - dynamic goto in loop (got $got, expected $expected)\n";
    }
}

