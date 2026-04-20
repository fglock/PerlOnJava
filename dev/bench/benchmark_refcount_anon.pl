#!/usr/bin/env perl
# benchmark_refcount_anon.pl
# Stresses anon array/hash literal construction, exercising the
# suppressFlush bytecode added in feature/refcount-alignment.
# Should be sensitive to regressions in EmitLiteral.java's per-literal
# overhead and to changes in MortalList.flush / createReferenceWithTrackedElements.

use strict;
use warnings;
use Benchmark qw(timethese cmpthese);

{ package Obj;
  sub new { my ($c,$id)=@_; bless { id => $id, tags => [1,2,3] }, $c }
  sub DESTROY {}  # keep blessed-with-DESTROY path hot
}

my $N = $ENV{BENCH_N} || 100_000;

sub array_of_scalars {
    my $arr = [ 1, 2, 3, 4, 5, 6, 7, 8 ];
    return scalar @$arr;
}

sub array_of_blessed {
    my $arr = [ Obj->new(1), Obj->new(2), Obj->new(3) ];
    return scalar @$arr;
}

sub hash_of_scalars {
    my $h = { a => 1, b => 2, c => 3, d => 4 };
    return scalar keys %$h;
}

sub hash_of_blessed {
    my $h = { a => Obj->new(1), b => Obj->new(2), c => Obj->new(3) };
    return scalar keys %$h;
}

sub nested_literal {
    my $x = {
        list => [ Obj->new(1), Obj->new(2) ],
        map  => { one => Obj->new(10), two => Obj->new(20) },
    };
    return 1;
}

timethese($N, {
    'array_of_scalars' => \&array_of_scalars,
    'array_of_blessed' => \&array_of_blessed,
    'hash_of_scalars'  => \&hash_of_scalars,
    'hash_of_blessed'  => \&hash_of_blessed,
    'nested_literal'   => \&nested_literal,
});
