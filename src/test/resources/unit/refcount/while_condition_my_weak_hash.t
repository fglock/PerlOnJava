#!/usr/bin/perl
use strict;
use warnings;
use Test::More tests => 2;
use Scalar::Util qw(isweak weaken refaddr);

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
    isweak($PARENT{refaddr($child)}),
    1,
    'weak parent starts as a weak hash value',
);

top_like($child) for 1 .. 20;

is(
    isweak($PARENT{refaddr($child)}),
    1,
    'while condition my weak hash lookup preserves the weak parent value',
);
