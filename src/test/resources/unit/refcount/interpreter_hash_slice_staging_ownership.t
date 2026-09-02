#!/usr/bin/env perl
use strict;
use warnings;

use Test::More;
use Test2::Tools::Refcount qw(is_refcount is_oneref);

{
    package InterpreterHashSliceStagingTarget;
    sub DESTROY { }
}

my $target = bless {}, 'InterpreterHashSliceStagingTarget';
my %slots;

@slots{'future'} = ($target);
is_refcount($target, 2,
    'hash-slice assignment has one lexical and one durable hash owner');

%slots = ();
is_oneref($target,
    'clearing a hash-slice destination releases its only non-lexical owner');

done_testing;
