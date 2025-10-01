#!/usr/bin/perl
use strict;
use warnings;
use Test::More tests => 10;

# Test x[template] construct in unpack
# This test verifies that x[template] correctly calculates template sizes
# and properly validates * (star) inside brackets.

subtest 'x[template] rejects * inside brackets (little-endian)' => sub {
    plan tests => 1;
    eval {
        my @list = (1, 2, 3, 4);
        my @end = (5, 6, 7, 8);
        my $p = pack "s<* I*", @list, @end;
        my @l = unpack "x[s<*] I*", $p;
    };
    like($@, qr/Within.*length.*not allowed/, 'rejects * in x[s<*]');
};

subtest 'x[template] rejects * inside brackets (big-endian)' => sub {
    plan tests => 1;
    eval {
        my @list = (1, 2, 3, 4);
        my @end = (5, 6, 7, 8);
        my $p = pack "s>* I*", @list, @end;
        my @l = unpack "x[s>*] I*", $p;
    };
    like($@, qr/Within.*length.*not allowed/, 'rejects * in x[s>*]');
};

subtest 'x[s<4] skips correct number of bytes' => sub {
    plan tests => 1;
    my @list = (1, 2, 3, 4);
    my @end = (5, 6, 7, 8);
    my $p = pack "s<4 I*", @list, @end;
    my @l = unpack "x[s<4] I*", $p;
    is("@l", "@end", 'x[s<4] skips 8 bytes correctly');
};

subtest 'x8 (simple numeric) works correctly' => sub {
    plan tests => 1;
    my @list = (1, 2, 3, 4);
    my @end = (5, 6, 7, 8);
    my $p = pack "s<4 I*", @list, @end;
    my @l = unpack "x8 I*", $p;
    is("@l", "@end", 'x8 skips 8 bytes correctly');
};

subtest 'x[(x)2] group with repeat count' => sub {
    plan tests => 1;
    my @end = (5, 6, 7, 8);
    my $p = pack "xx I*", @end;
    my @l = unpack "x[(x)2] I*", $p;
    is("@l", "@end", 'x[(x)2] skips 2 bytes correctly');
};

subtest 'x[x3] format with count' => sub {
    plan tests => 1;
    my @end = (5, 6, 7, 8);
    my $p = pack "xxx I*", @end;
    my @l = unpack "x[x3] I*", $p;
    is("@l", "@end", 'x[x3] skips 3 bytes correctly');
};

subtest 'x[s X] handles negative offset' => sub {
    plan tests => 1;
    my $p = pack "s I*", 42, (1, 2, 3, 4);
    my @l = unpack "x[s X] I*", $p;
    my @expected = unpack "x1 I*", $p;
    is("@l", "@expected", 'x[s X] calculates net offset correctly');
};

subtest 'x[s2] skips 4 bytes' => sub {
    plan tests => 1;
    my $p = pack "C8", 1..8;
    my @l = unpack "x[s2] C*", $p;
    is("@l", "5 6 7 8", 'x[s2] skips 4 bytes (2 shorts)');
};

subtest 'x[A x!8] handles native size modifier' => sub {
    plan tests => 1;
    my @end = (1, 2, 3, 4);
    my $p = pack "A x!8 I*", "test", @end;
    my @l = unpack "x[A x!8] I*", $p;
    is("@l", "@end", 'x[A x!8] calculates size with native modifier');
};

subtest 'x[(A)2] handles group correctly' => sub {
    plan tests => 1;
    my @end = (1, 2, 3, 4);
    my $p = pack "(A)2 I*", "a", "b", @end;
    my @l = unpack "x[(A)2] I*", $p;
    is("@l", "@end", 'x[(A)2] skips group correctly');
};
