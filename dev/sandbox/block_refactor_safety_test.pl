#!/usr/bin/perl
use strict;
use warnings;

print "=== Testing Block Refactoring Safety ===\n\n";

# Test 1: Loop with next jumping to outer loop
print "Test 1: next to outer loop\n";
OUTER: for my $i (1..2) {
    print "  Outer loop i=$i\n";
    
    # This block is large enough to potentially trigger refactoring
    for my $j (1..20) {
        print "    Inner j=$j: ";
        print "stmt1 "; print "stmt2 "; print "stmt3 "; print "stmt4 ";
        print "stmt5 "; print "stmt6 "; print "stmt7 "; print "stmt8 ";
        print "stmt9 "; print "stmt10 "; print "stmt11 "; print "stmt12 ";
        print "stmt13 "; print "stmt14 "; print "stmt15 "; print "stmt16 ";
        print "stmt17 "; print "stmt18 "; print "stmt19 "; print "stmt20 ";
        
        if ($j == 2) {
            print "next OUTER\n";
            next OUTER;  # This should jump to outer loop, not inner!
        }
        print "done\n";
    }
    print "  After inner loop\n";
}

print "\nTest 2: goto to label outside block\n";
my $count = 0;
RESTART:
$count++;
print "  Attempt $count\n";

# Large block that might be refactored
{
    print "    In block: ";
    print "stmt1 "; print "stmt2 "; print "stmt3 "; print "stmt4 ";
    print "stmt5 "; print "stmt6 "; print "stmt7 "; print "stmt8 ";
    print "stmt9 "; print "stmt10 "; print "stmt11 "; print "stmt12 ";
    print "stmt13 "; print "stmt14 "; print "stmt15 "; print "stmt16 ";
    print "stmt17 "; print "stmt18 "; print "stmt19 "; print "stmt20 ";
    
    if ($count < 2) {
        print "goto RESTART\n";
        goto RESTART;  # This should jump outside the block!
    }
    print "done\n";
}
print "  After block\n";

print "\n=== Done ===\n";
