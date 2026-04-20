#!/usr/bin/env perl
# benchmark_anon_simple.pl
# Measures the overhead of anon array/hash literal construction for
# SIMPLE elements (no sub/method calls). These paths should skip the
# MortalList.suppressFlush wrapper entirely after the J2 optimization.

use strict;
use warnings;
use Benchmark qw(timethese);

my $N = $ENV{BENCH_N} || 1_000_000;

sub arr_int {
    my $a = [ 1, 2, 3, 4, 5, 6, 7, 8 ];
    return scalar @$a;
}

sub arr_str {
    my $a = [ "one", "two", "three", "four" ];
    return scalar @$a;
}

sub arr_var {
    my ($x, $y) = (10, 20);
    my $a = [ $x, $y, $x + $y, $x * $y ];
    return scalar @$a;
}

sub hash_simple {
    my $h = { a => 1, b => 2, c => 3, d => 4, e => 5 };
    return scalar keys %$h;
}

sub nested_simple {
    my $x = { a => [1,2,3], b => [4,5,6], c => { x => 10, y => 20 } };
    return 1;
}

timethese($N, {
    'arr_int'       => \&arr_int,
    'arr_str'       => \&arr_str,
    'arr_var'       => \&arr_var,
    'hash_simple'   => \&hash_simple,
    'nested_simple' => \&nested_simple,
});
