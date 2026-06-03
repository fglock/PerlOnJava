#!/usr/bin/perl
use strict;
use warnings;
use Test::More tests => 2;
use Scalar::Util qw(weaken refaddr);
use Internals;

our %PARENT;

{
    package WhileConditionMyWeakHashObject;
    sub DESTROY {}
}

sub top_like {
    my $cursor = shift;
    while (my $parent = $PARENT{refaddr($cursor)}) {
        $cursor = $parent;
    }
    $cursor;
}

my $root  = bless {}, 'WhileConditionMyWeakHashObject';
my $child = bless {}, 'WhileConditionMyWeakHashObject';
weaken($PARENT{refaddr($child)} = $root);

is(
    Internals::jperl_refstate_str($root),
    'HASH:WhileConditionMyWeakHashObject:0:W',
    'weak parent starts without counted strong refs',
);

top_like($child) for 1 .. 20;

is(
    Internals::jperl_refstate_str($root),
    'HASH:WhileConditionMyWeakHashObject:0:W',
    'while condition my weak hash lookup does not leak parent refs',
);
