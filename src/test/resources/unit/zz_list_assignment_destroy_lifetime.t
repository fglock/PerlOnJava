#!/usr/bin/env perl

use strict;
use warnings;
use Scalar::Util qw(refaddr);
use Test::More tests => 3;

{
    package Local::TrackedSet;

    my %state;

    sub new {
        my $class = shift;
        my $token = '';
        my $self = bless \$token, $class;
        $state{Scalar::Util::refaddr($self)} = { members => {} };
        $self->insert(@_);
        return $self;
    }

    sub insert {
        my $self = shift;
        my $members = $state{Scalar::Util::refaddr($self)}{members};
        $members->{$_} = 1 for @_;
    }

    sub members {
        my $self = shift;
        my $entry = $state{Scalar::Util::refaddr($self)};
        return $entry ? keys %{ $entry->{members} } : ();
    }

    sub DESTROY {
        my $self = shift;
        delete $state{Scalar::Util::refaddr($self)};
    }
}

my @pairs = ([0, 1], [1, 2], [2, 3]);
my ($predecessors, $successors, @scratch) = (
    Local::TrackedSet->new(map $_->[0], @pairs),
    Local::TrackedSet->new(map $_->[1], @pairs),
);

is_deeply([sort $predecessors->members], [0, 1, 2],
    'the first returned object remains alive after list assignment');
is_deeply([sort $successors->members], [1, 2, 3],
    'the second returned object remains alive after list assignment');
is(scalar @scratch, 0,
    'the trailing slurpy target does not consume assigned objects');
