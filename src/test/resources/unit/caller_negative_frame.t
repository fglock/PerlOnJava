#!/usr/bin/perl
use strict;
use warnings;
use Test::More tests => 6;

sub inspect_negative_frames {
    my @minus_one = caller(-1);
    my @minus_two = caller(-2);
    my $scalar_minus_one = caller(-1);
    my $scalar_minus_two = caller(-2);
    return (\@minus_one, \@minus_two, $scalar_minus_one, $scalar_minus_two);
}

sub outer { inspect_negative_frames() }

my ($minus_one, $minus_two, $scalar_minus_one, $scalar_minus_two) = outer();
is(scalar(@$minus_one), 0, 'caller(-1) returns an empty list');
is(scalar(@$minus_two), 0, 'caller(-2) returns an empty list');
ok(!defined($scalar_minus_one), 'scalar caller(-1) is undef');
ok(!defined($scalar_minus_two), 'scalar caller(-2) is undef');

my @top_level = caller(-1);
is(scalar(@top_level), 0, 'top-level caller(-1) returns an empty list');
ok(!defined(scalar caller(-1)), 'top-level scalar caller(-1) is undef');
