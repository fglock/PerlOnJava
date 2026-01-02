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
print "=== INVESTIGATION RESULTS ===\n";
print "The non-local control flow feature requires checking the registry IMMEDIATELY after\n";
print "subroutine calls return (in EmitSubroutine.handleApplyOperator).\n";
print "\n";
print "IMPLEMENTATION ATTEMPTED:\n";
print "- Added emitControlFlowCheck() that uses TABLESWITCH to jump based on registry action\n";
print "- This works perfectly for simple cases (test passes!)\n";
print "- BUT: TABLESWITCH causes ASM frame computation errors in nested/refactored contexts\n";
print "- Error: 'ArrayIndexOutOfBoundsException: Index 0 out of bounds' in ASM Frame.merge()\n";
print "\n";
print "THE PROBLEM:\n";
print "- TABLESWITCH creates complex control flow with multiple branch targets\n";
print "- ASM's automatic frame computation fails when TABLESWITCH is in nested anonymous\n";
print "  subroutines created by LargeBlockRefactorer\n";
print "- pack_utf8.t and other tests fail with ASM errors when the check is enabled\n";
print "\n";
print "POSSIBLE SOLUTIONS:\n";
print "1. Use IF chains instead of TABLESWITCH (but this also causes ASM issues)\n";
print "2. Pre-allocate temp slots at method entry (avoid dynamic allocation)\n";
print "3. Only enable checks in non-refactored contexts (detect LargeBlockRefactorer subs)\n";
print "4. Use manual frame hints with visitFrame() at merge points\n";
print "5. Redesign to avoid checking after every call (performance impact)\n";
print "\n";
print "CURRENT STATUS: Feature disabled due to ASM limitations\n";
