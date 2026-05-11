#!/usr/bin/env perl
use strict;
use warnings;

use Test::More;
use Test2::Tools::Refcount qw(is_refcount is_oneref);
use Test2::Tools::Ref qw(ref_is);
use B qw(svref_2object);

{
    package T2RefcountLoopish;

    sub new {
        my $self = bless {}, shift;
        our $ONE_TRUE ||= $self;
        return $self;
    }
}

my $object = T2RefcountLoopish->new;

is(svref_2object($object)->REFCNT, 2, 'direct count sees lexical and package global');
is_refcount($object, 2, 'Test2 refcount helper preserves lexical and package global');
is(svref_2object($object)->REFCNT, 2, 'helper does not consume a counted owner');

sub assert_same_ref {
    ref_is($_[0], $object, 'Test2 ref helper sees identical object');
}

assert_same_ref($object);
is(svref_2object($object)->REFCNT, 2, 'ref helper does not consume a counted owner');

{
    package T2RefcountSingle;
    sub new { bless {}, shift }
}

my $single = T2RefcountSingle->new;

is(svref_2object($single)->REFCNT, 1, 'single object starts with one owner');
is_oneref($single, 'Test2 one-ref helper reports one owner');
is(svref_2object($single)->REFCNT, 1, 'one-ref helper does not consume a counted owner');

done_testing;
