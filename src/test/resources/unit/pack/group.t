#!/usr/bin/perl
use strict;
use warnings;
use Test::More tests => 5;

# Test pack group valueIndex tracking
# This test verifies that pack groups correctly track the value index
# and don't skip values when followed by other formats.

subtest '(A)2 produces same result as A A' => sub {
    plan tests => 1;
    my $p1 = pack "(A)2", "a", "b";
    my $expected1 = pack "A A", "a", "b";
    is($p1, $expected1, 'group packing matches individual formats');
};

subtest '(A)2 I produces same result as A A I' => sub {
    plan tests => 1;
    my $p2 = pack "(A)2 I", "a", "b", 99;
    my $expected2 = pack "A A I", "a", "b", 99;
    is($p2, $expected2, 'group followed by integer packs correctly');
};

subtest '(A)2 I* produces same result as A A I*' => sub {
    plan tests => 1;
    my @ints = (1, 2, 3, 4);
    my $p3 = pack "(A)2 I*", "a", "b", @ints;
    my $expected3 = pack "A A I*", "a", "b", @ints;
    is($p3, $expected3, 'group followed by multiple integers packs correctly');
};

subtest '(C)2 I produces same result as C C I' => sub {
    plan tests => 1;
    my $p4 = pack "(C)2 I", 1, 2, 99;
    my $expected4 = pack "C C I", 1, 2, 99;
    is($p4, $expected4, 'numeric group followed by integer packs correctly');
};

subtest '(s)2 I produces same result as s s I' => sub {
    plan tests => 1;
    my $p5 = pack "(s)2 I", 10, 20, 99;
    my $expected5 = pack "s s I", 10, 20, 99;
    is($p5, $expected5, 'short group followed by integer packs correctly');
};
