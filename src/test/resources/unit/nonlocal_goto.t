#!/usr/bin/env perl

use strict;
use warnings;
use feature 'say';

print "1..11\n";

# Test 1: Basic non-local last
{
    my $result = 0;
    
    sub outer1 {
        OUTER: for my $i (1..10) {
            inner1();
            $result = $i;
        }
    }
    
    sub inner1 {
        for my $j (1..5) {
            last OUTER if $j == 3;
        }
    }
    
    outer1();
    if ($result == 0) {
        print "ok 1 - non-local last OUTER from inner sub\n";
    } else {
        print "not ok 1 - non-local last OUTER from inner sub (got $result)\n";
    }
}

# Test 2: Non-local next
{
    my @results = ();
    
    sub outer2 {
        LOOP: for my $i (1..5) {
            inner2($i);
            push @results, $i;
        }
    }
    
    sub inner2 {
        my $n = shift;
        next LOOP if $n % 2 == 0;
    }
    
    outer2();
    my $expected = join(',', 1, 3, 5);
    my $got = join(',', @results);
    if ($got eq $expected) {
        print "ok 2 - non-local next LOOP from inner sub\n";
    } else {
        print "not ok 2 - non-local next LOOP from inner sub (got $got, expected $expected)\n";
    }
}

# Test 3: Non-local redo
{
    my $count = 0;
    
    sub outer3 {
        REDO_LOOP: for my $i (1..3) {
            $count++;
            last REDO_LOOP if $count > 5;
            inner3($i);
        }
    }
    
    sub inner3 {
        my $n = shift;
        redo REDO_LOOP if $n == 2 && $count < 5;
    }
    
    outer3();
    if ($count > 3) {
        print "ok 3 - non-local redo REDO_LOOP from inner sub (count=$count)\n";
    } else {
        print "not ok 3 - non-local redo REDO_LOOP from inner sub (count=$count)\n";
    }
}

# Test 4: Non-local goto
{
    my $reached = 0;
    
    sub goto_outer {
        inner_goto();
        $reached = 1;
        LABEL: $reached = 2;
    }
    
    sub inner_goto {
        goto LABEL;
    }
    
    goto_outer();
    if ($reached == 2) {
        print "ok 4 - non-local goto LABEL from inner sub\n";
    } else {
        print "not ok 4 - non-local goto LABEL from inner sub (reached=$reached)\n";
    }
}

# Test 5: Multiple call levels
{
    my $level = 0;
    
    sub level1 {
        OUTER: for my $i (1..5) {
            level2($i);
            $level = 1;
        }
    }
    
    sub level2 {
        my $n = shift;
        level3($n);
    }
    
    sub level3 {
        my $n = shift;
        last OUTER if $n == 3;
    }
    
    level1();
    if ($level == 1) {
        print "ok 5 - non-local last through multiple call levels\n";
    } else {
        print "not ok 5 - non-local last through multiple call levels (level=$level)\n";
    }
}

# Test 6: Unlabeled next (should fail or work with innermost loop)
{
    my $ok = 1;
    eval {
        sub unlabeled_outer {
            for my $i (1..3) {
                unlabeled_inner();
            }
        }
        
        sub unlabeled_inner {
            for my $j (1..3) {
                # This should only affect the inner loop, not outer
                next if $j == 2;
            }
        }
        
        unlabeled_outer();
    };
    if ($ok && !$@) {
        print "ok 6 - unlabeled next works correctly\n";
    } else {
        print "not ok 6 - unlabeled next failed: $@\n";
    }
}

# Test 7: Nested loops with labels
{
    my @path = ();
    
    sub nested_outer {
        OUTER: for my $i (1..3) {
            push @path, "outer:$i";
            INNER: for my $j (1..3) {
                push @path, "inner:$j";
                nested_inner($i, $j);
            }
        }
    }
    
    sub nested_inner {
        my ($i, $j) = @_;
        last OUTER if $i == 2 && $j == 2;
        next INNER if $j == 1;
    }
    
    nested_outer();
    my $path_str = join(',', @path);
    # Should have outer:1, inner:1,2,3, outer:2, inner:1,2
    if ($path_str =~ /outer:1.*inner:1.*inner:2.*inner:3.*outer:2.*inner:1.*inner:2/) {
        print "ok 7 - nested loops with non-local jumps\n";
    } else {
        print "not ok 7 - nested loops with non-local jumps (path=$path_str)\n";
    }
}

# Test 8: Non-existent label (should throw exception)
{
    my $caught = 0;
    eval {
        sub no_label_sub {
            for my $i (1..3) {
                last NO_SUCH_LABEL;
            }
        }
        no_label_sub();
    };
    if ($@ && $@ =~ /NO_SUCH_LABEL|label not found|outside a loop/i) {
        print "ok 8 - non-existent label throws error\n";
    } else {
        print "not ok 8 - non-existent label should throw error (got: $@)\n";
    }
}

# Test 9: SKIP block pattern (like Test::More)
{
    my $skipped = 0;
    my $executed = 0;
    
    sub skip_test {
        SKIP: {
            $skipped = 1;
            helper_skip();
            $executed = 1;
        }
    }
    
    sub helper_skip {
        last SKIP;  # Should exit the SKIP block
    }
    
    skip_test();
    if ($skipped == 1 && $executed == 0) {
        print "ok 9 - SKIP block pattern works\n";
    } else {
        print "not ok 9 - SKIP block pattern (skipped=$skipped, executed=$executed)\n";
    }
}

# Test 10: Local vs non-local - local should still work
{
    my @results = ();
    
    sub local_loop {
        for my $i (1..5) {
            last if $i == 3;
            push @results, $i;
        }
    }
    
    local_loop();
    my $expected = join(',', 1, 2);
    my $got = join(',', @results);
    if ($got eq $expected) {
        print "ok 10 - local last still works (no regression)\n";
    } else {
        print "not ok 10 - local last regression (got $got, expected $expected)\n";
    }
}

# Test 11: Non-local with block label
# DISABLED: This doesn't work in standard Perl either - causes infinite loop
# goto to block labels jumps to the block start, not after it
{
    # my $reached_label = 0;
    # 
    # sub test_block_label {
    #     TEST_LABEL: {
    #         inner_block_goto();
    #         $reached_label = 1;
    #     }
    #     $reached_label = 2;
    # }
    # 
    # sub inner_block_goto {
    #     goto TEST_LABEL;
    # }
    # 
    # test_block_label();
    # if ($reached_label == 2) {
    #     print "ok 11 - non-local goto to block label\n";
    # } else {
    #     print "not ok 11 - non-local goto to block label (reached_label=$reached_label)\n";
    # }
    print "ok 11 # skip - goto to block labels causes infinite loop in standard Perl\n";
}

