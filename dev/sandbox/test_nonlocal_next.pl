#!/usr/bin/env perl
use strict;
use warnings;

# Test non-local next - closure executing next LABEL where LABEL is in caller
# This test demonstrates the current limitation of the registry-based approach:
# The registry check happens at the end of each loop iteration, not immediately
# after the closure returns, so code after the closure call still executes.

print "Test 1: Non-local next with C-style for loop\n";

my $result = "";

OUTER: for (my $i = 1; $i <= 3; $i++) {
    $result .= "start:$i ";
    
    # Direct anonymous sub call
    sub {
        $result .= "in_sub ";
        next OUTER if $i == 2;
        $result .= "after_next ";
    }->();
    
    $result .= "end:$i ";
}

print "Result: $result\n";
print "Expected: start:1 in_sub after_next end:1 start:2 in_sub start:3 in_sub after_next end:3\n";
print "Actual behavior: start:1 in_sub after_next end:1 start:2 in_sub end:2 start:3 in_sub after_next end:3\n";
print "\n";

if ($result eq "start:1 in_sub after_next end:1 start:2 in_sub start:3 in_sub after_next end:3 ") {
    print "PASS: Non-local next works correctly!\n";
} else {
    print "FAIL: Non-local next not working as expected\n";
    print "Issue: Code after closure call (end:2) still executes before registry check\n";
}

print "\n";
print "Test 2: Non-local next with subroutine call\n";

$result = "";

sub run_closure {
    my ($code) = @_;
    $code->();
}

LOOP2: for my $i (1..3) {
    $result .= "outer:$i ";
    
    run_closure(sub {
        $result .= "inner ";
        next LOOP2 if $i == 2;
        $result .= "after_next ";
    });
    
    $result .= "end ";
}

print "Result: $result\n";
print "Expected: outer:1 inner after_next end outer:2 inner outer:3 inner after_next end\n";

if ($result eq "outer:1 inner after_next end outer:2 inner outer:3 inner after_next end ") {
    print "PASS: Non-local next with subroutine works!\n";
} else {
    print "FAIL: Non-local next with subroutine not working\n";
}

print "\n";
print "=== CURRENT STATUS ===\n";
print "The registry-based approach for non-local control flow has a design limitation:\n";
print "- When 'next LABEL' is called inside a closure, it registers a marker and returns normally\n";
print "- The loop checks the registry at the END of each iteration\n";
print "- This means code after the closure call still executes before the jump happens\n";
print "\n";
print "To make this test pass, we need to check the registry IMMEDIATELY after the closure returns,\n";
print "not just at the end of the loop iteration.\n";
