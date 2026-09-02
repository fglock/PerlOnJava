#!/usr/bin/env perl
use strict;
use warnings;

use Test::More;
use Test2::Tools::Refcount qw(is_oneref);
use B qw(svref_2object);

{
    package BRefcountFreshLexical;
    sub new { bless {}, shift }
}

# Keep this in its own test process.  B::SV must not depend on unrelated
# earlier activity registering a live lexical with the reachability walker.
my $object = BRefcountFreshLexical->new;

is(svref_2object($object)->REFCNT, 1,
    'B reports the single lexical owner in a fresh runtime');
is_oneref($object,
    'Test2 observes the same single owner through B');

done_testing;
