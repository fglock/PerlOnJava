use strict;
use warnings;
use B qw(svref_2object);
use Test::More tests => 3;

sub refcount { svref_2object($_[0])->REFCNT }

{
    package ForeachAliasRefcountNode;

    sub new { bless { children => [] }, shift }
}

my $parent = ForeachAliasRefcountNode->new;
my $child = ForeachAliasRefcountNode->new;
push @{$parent->{children}}, $child;

is(refcount($child), 2, 'child has one lexical and one array owner');

for (@{$parent->{children}}) {
    is(refcount($_), 2, 'foreach aliases the element cell without adding a referent owner');
}

is(refcount($child), 2, 'leaving foreach preserves the original two owners');

done_testing;
