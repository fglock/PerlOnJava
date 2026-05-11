#!/usr/bin/env perl
use strict;
use warnings;

use Test::More;
use Scalar::Util qw(weaken);
use B qw(svref_2object);

sub rc { svref_2object($_[0])->REFCNT }

{
    package ForeachRefNode;

    sub new {
        bless { children => [] }, shift;
    }

    sub add_child {
        my ($self, $child) = @_;
        push @{ $self->{children} }, $child;
        $child->{parent} = $self;
        Scalar::Util::weaken($child->{parent});
    }

    sub children {
        return @{ $_[0]->{children} };
    }
}

my $parent = ForeachRefNode->new;
my $child  = ForeachRefNode->new;

$parent->add_child($child);

is(rc($child), 2, 'child starts with lexical and parent array owners');

for ($parent->children) {
    is(rc($_), 2, 'implicit $_ foreach aliases returned child without extra owner');
}

is(rc($child), 2, 'restoring implicit $_ does not consume a child owner');

done_testing;
