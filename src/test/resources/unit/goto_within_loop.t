#!/usr/bin/env perl

use strict;
use warnings;

print "1..6\n";

# Test 1: Static goto to label inside same loop
{
    my @results = ();
    for my $i (1..5) {
        push @results, "before$i";
        goto INSIDE if $i == 3;
        push @results, "middle$i";
        INSIDE:
        push @results, "after$i";
    }
    
    my $expected = "before1,middle1,after1,before2,middle2,after2,before3,after3,before4,middle4,after4,before5,middle5,after5";
    my $got = join(',', @results);
    
    if ($got eq $expected) {
        print "ok 1 - static goto to label inside same loop\n";
    } else {
        print "not ok 1 - static goto to label inside same loop\n";
        print "# Expected: $expected\n";
        print "# Got: $got\n";
    }
}

# Test 2: Dynamic goto to label inside same loop
{
    my @results = ();
    my $target = "INSIDE";
    
    for my $i (1..5) {
        push @results, "before$i";
        goto $target if $i == 2;
        push @results, "middle$i";
        INSIDE:
        push @results, "after$i";
    }
    
    my $expected = "before1,middle1,after1,before2,after2,before3,middle3,after3,before4,middle4,after4,before5,middle5,after5";
    my $got = join(',', @results);
    
    if ($got eq $expected) {
        print "ok 2 - dynamic goto to label inside same loop\n";
    } else {
        print "not ok 2 - dynamic goto to label inside same loop\n";
        print "# Expected: $expected\n";
        print "# Got: $got\n";
    }
}

# Test 3: Static goto to label at start of loop body
{
    my @results = ();
    my $count = 0;
    for my $i (1..5) {
        START:
        push @results, "at_start$i";
        $count++;
        goto START if $i == 5 && $count == 5;
        push @results, "at_end$i";
    }
    
    my $expected = "at_start1,at_end1,at_start2,at_end2,at_start3,at_end3,at_start4,at_end4,at_start5,at_start5,at_end5";
    my $got = join(',', @results);
    
    if ($got eq $expected) {
        print "ok 3 - static goto to label at start of loop body\n";
    } else {
        print "not ok 3 - static goto to label at start of loop body\n";
        print "# Expected: $expected\n";
        print "# Got: $got\n";
    }
}

# Test 4: Multiple labels in same loop
{
    my @results = ();
    for my $i (1..4) {
        push @results, "start$i";
        goto LABEL2 if $i == 2;
        LABEL1:
        push @results, "label1_$i";
        goto LABEL3 if $i == 1;
        LABEL2:
        push @results, "label2_$i";
        LABEL3:
        push @results, "end$i";
    }
    
    my $expected = "start1,label1_1,end1,start2,label2_2,end2,start3,label1_3,label2_3,end3,start4,label1_4,label2_4,end4";
    my $got = join(',', @results);
    
    if ($got eq $expected) {
        print "ok 4 - multiple labels in same loop with selective goto\n";
    } else {
        print "not ok 4 - multiple labels in same loop with selective goto\n";
        print "# Expected: $expected\n";
        print "# Got: $got\n";
    }
}

# Test 5: Goto inside nested loops to label in inner loop
{
    my @results = ();
    for my $i (1..3) {
        for my $j (1..3) {
            push @results, "$i-$j-before";
            goto INNER if $i == 2 && $j == 2;
            push @results, "$i-$j-middle";
            INNER:
            push @results, "$i-$j-after";
        }
    }
    
    # When i=2, j=2: should skip middle, go directly to after
    my $got = join(',', @results);
    
    if ($got =~ /2-2-before,2-2-after/ && $got !~ /2-2-middle/) {
        print "ok 5 - goto to label in inner loop\n";
    } else {
        print "not ok 5 - goto to label in inner loop\n";
        print "# Got: $got\n";
    }
}

# Test 6: Goto with dynamic label selection inside loop
{
    my @results = ();
    for my $i (1..3) {
        my $label = ($i == 2) ? "SKIP" : "NORMAL";
        push @results, "before$i";
        goto $label;
        push @results, "skipped$i";
        SKIP:
        push @results, "skip$i" if $i == 2;
        NORMAL:
        push @results, "normal$i";
    }
    
    my $expected = "before1,normal1,before2,skip2,normal2,before3,normal3";
    my $got = join(',', @results);
    
    if ($got eq $expected) {
        print "ok 6 - dynamic label selection inside loop\n";
    } else {
        print "not ok 6 - dynamic label selection inside loop\n";
        print "# Expected: $expected\n";
        print "# Got: $got\n";
    }
}

