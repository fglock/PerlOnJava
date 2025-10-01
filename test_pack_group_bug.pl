#!/usr/bin/perl
use strict;
use warnings;

print "1..5\n";

# Test 1: Simple (A)2 packing
my $p1 = pack "(A)2", "a", "b";
my $expected1 = pack "A A", "a", "b";
if ($p1 eq $expected1) {
    print "ok 1 - (A)2 produces same result as A A\n";
} else {
    print "not ok 1 - (A)2 vs A A: got ", unpack("H*", $p1), " expected ", unpack("H*", $expected1), "\n";
}

# Test 2: (A)2 followed by I
my $p2 = pack "(A)2 I", "a", "b", 99;
my $expected2 = pack "A A I", "a", "b", 99;
if ($p2 eq $expected2) {
    print "ok 2 - (A)2 I produces same result as A A I\n";
} else {
    print "not ok 2 - (A)2 I vs A A I\n";
    print "#   (A)2 I: ", unpack("H*", $p2), " (length=", length($p2), ")\n";
    print "#   A A I:  ", unpack("H*", $expected2), " (length=", length($expected2), ")\n";
}

# Test 3: (A)2 followed by I* (multiple integers)
my @ints = (1, 2, 3, 4);
my $p3 = pack "(A)2 I*", "a", "b", @ints;
my $expected3 = pack "A A I*", "a", "b", @ints;
if ($p3 eq $expected3) {
    print "ok 3 - (A)2 I* produces same result as A A I*\n";
} else {
    print "not ok 3 - (A)2 I* vs A A I*\n";
    print "#   (A)2 I*: ", unpack("H*", $p3), " (length=", length($p3), ")\n";
    print "#   A A I*:  ", unpack("H*", $expected3), " (length=", length($expected3), ")\n";
}

# Test 4: Check if the issue is specific to A format
my $p4 = pack "(C)2 I", 1, 2, 99;
my $expected4 = pack "C C I", 1, 2, 99;
if ($p4 eq $expected4) {
    print "ok 4 - (C)2 I produces same result as C C I\n";
} else {
    print "not ok 4 - (C)2 I vs C C I\n";
    print "#   (C)2 I: ", unpack("H*", $p4), " (length=", length($p4), ")\n";
    print "#   C C I:  ", unpack("H*", $expected4), " (length=", length($expected4), ")\n";
}

# Test 5: Check if the issue is with groups in general
my $p5 = pack "(s)2 I", 10, 20, 99;
my $expected5 = pack "s s I", 10, 20, 99;
if ($p5 eq $expected5) {
    print "ok 5 - (s)2 I produces same result as s s I\n";
} else {
    print "not ok 5 - (s)2 I vs s s I\n";
    print "#   (s)2 I: ", unpack("H*", $p5), " (length=", length($p5), ")\n";
    print "#   s s I:  ", unpack("H*", $expected5), " (length=", length($expected5), ")\n";
}
