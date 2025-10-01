#!/usr/bin/perl
use strict;
use warnings;

print "1..10\n";

# Test 1: Standard Perl should reject * inside x[template]
my $test1_result = eval {
    my @list = (1, 2, 3, 4);
    my @end = (5, 6, 7, 8);
    my $p = pack "s<* I*", @list, @end;
    my @l = unpack "x[s<*] I*", $p;
    return "no_error";
};
if ($@) {
    if ($@ =~ /Within.*length.*not allowed/) {
        print "ok 1 - Perl correctly rejects * inside x[template]\n";
    } else {
        print "not ok 1 - Perl error but wrong message: $@\n";
    }
} else {
    print "not ok 1 - Perl should have thrown error but got: $test1_result\n";
}

# Test 2: x[s<4] should work (numeric count)
my @list = (1, 2, 3, 4);
my @end = (5, 6, 7, 8);
my $p = pack "s<4 I*", @list, @end;
my @l = unpack "x[s<4] I*", $p;
if ("@l" eq "@end") {
    print "ok 2 - x[s<4] works correctly\n";
} else {
    print "not ok 2 - x[s<4] failed: got '@l', expected '@end'\n";
}

# Test 3: x8 should work (simple numeric count)
$p = pack "s<4 I*", @list, @end;
@l = unpack "x8 I*", $p;
if ("@l" eq "@end") {
    print "ok 3 - x8 works correctly\n";
} else {
    print "not ok 3 - x8 failed: got '@l', expected '@end'\n";
}

# Test 4: x[s>*] should also be rejected
my $test4_result = eval {
    my @list = (1, 2, 3, 4);
    my @end = (5, 6, 7, 8);
    my $p = pack "s>* I*", @list, @end;
    my @l = unpack "x[s>*] I*", $p;
    return "no_error";
};
if ($@) {
    if ($@ =~ /Within.*length.*not allowed/) {
        print "ok 4 - Perl correctly rejects * inside x[template] (big-endian)\n";
    } else {
        print "not ok 4 - Perl error but wrong message: $@\n";
    }
} else {
    print "not ok 4 - Perl should have thrown error but got: $test4_result\n";
}

# Test 5: x[x x1] - from actual failing test 4430
# This is: x format, then x1 (skip 1 byte)
$p = pack "x I*", @end;  # Pack with x (null byte) then integers
@l = unpack "x[x x1] I*", $p;
my $expected5 = join(" ", @end);
if ("@l" eq $expected5) {
    print "ok 5 - x[x x1] works correctly\n";
} else {
    print "not ok 5 - x[x x1] failed: got '@l', expected '$expected5'\n";
}

# Test 6: x[x x!8] - from actual failing test 4432
$p = pack "x I*", @end;
@l = unpack "x[x x!8] I*", $p;
if ("@l" eq $expected5) {
    print "ok 6 - x[x x!8] works correctly\n";
} else {
    print "not ok 6 - x[x x!8] failed: got '@l', expected '$expected5'\n";
}

# Test 7: x[(x)2] - from actual failing test 4436
# This is: group of x, repeated 2 times
$p = pack "xx I*", @end;  # Pack with 2 null bytes then integers
@l = unpack "x[(x)2] I*", $p;
if ("@l" eq $expected5) {
    print "ok 7 - x[(x)2] works correctly\n";
} else {
    print "not ok 7 - x[(x)2] failed: got '@l', expected '$expected5'\n";
}

# Test 8: x[x3] - from actual failing test 4460
$p = pack "xxx I*", @end;  # Pack with 3 null bytes then integers
@l = unpack "x[x3] I*", $p;
if ("@l" eq $expected5) {
    print "ok 8 - x[x3] works correctly\n";
} else {
    print "not ok 8 - x[x3] failed: got '@l', expected '$expected5'\n";
}

# Test 9: x[s X] - X should work with other formats
$p = pack "s I*", 42, @end;
@l = unpack "x[s X] I*", $p;  # Skip 2 bytes (s), back 1 byte (X) = net 1 byte
# After skipping 1 byte, we should read from position 1
my @expected9 = unpack "x1 I*", $p;
if ("@l" eq "@expected9") {
    print "ok 9 - x[s X] works correctly\n";
} else {
    print "not ok 9 - x[s X] failed: got '@l', expected '@expected9'\n";
}

# Test 10: Verify baseline - simple x[s2] should skip 4 bytes
$p = pack "C8", 1..8;
@l = unpack "x[s2] C*", $p;
if ("@l" eq "5 6 7 8") {
    print "ok 10 - x[s2] skips 4 bytes correctly\n";
} else {
    print "not ok 10 - x[s2] failed: got '@l', expected '5 6 7 8'\n";
}
