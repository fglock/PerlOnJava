#!/usr/bin/env perl

use strict;
use warnings;

print "1..3\n";

# Test 1: Simple local last
{
    my $result = 0;
    for my $i (1..10) {
        last if $i == 3;
        $result = $i;
    }
    if ($result == 2) {
        print "ok 1 - simple local last\n";
    } else {
        print "not ok 1 - simple local last (result=$result)\n";
    }
}

# Test 2: Simple local next
{
    my @results = ();
    for my $i (1..5) {
        next if $i % 2 == 0;
        push @results, $i;
    }
    my $expected = join(',', 1, 3, 5);
    my $got = join(',', @results);
    if ($got eq $expected) {
        print "ok 2 - simple local next\n";
    } else {
        print "not ok 2 - simple local next (got $got)\n";
    }
}

# Test 3: Simple labeled last
{
    my $result = 0;
    OUTER: for my $i (1..10) {
        last OUTER if $i == 3;
        $result = $i;
    }
    if ($result == 2) {
        print "ok 3 - simple labeled last\n";
    } else {
        print "not ok 3 - simple labeled last (result=$result)\n";
    }
}

