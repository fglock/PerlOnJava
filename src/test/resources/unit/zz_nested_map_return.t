#!/usr/bin/env perl

use strict;
use warnings;
use Test::More tests => 11;

sub ids_for_paths {
    my ($paths) = @_;
    return map {
        my @ids = map {
            $_ eq 'missing' ? return : uc($_)
        } @$_;
        \@ids;
    } @$paths;
}

my @complete = ids_for_paths([ [qw(a b)], [qw(c)] ]);
is(scalar @complete, 2, 'nested map returns each completed path');
is_deeply($complete[0], [qw(A B)], 'first nested map result is preserved');
is_deeply($complete[1], [qw(C)], 'second nested map result is preserved');

my $continued = 0;
my @missing = ids_for_paths([ [qw(a missing)], [qw(c)] ]);
$continued = 1;
is(scalar @missing, 0, 'return inside nested map exits the owning subroutine');
ok($continued, 'nested map return does not escape into the caller');

sub implicit_ids_for_paths {
    my ($paths) = @_;
    map {
        my @ids = map {
            $_ eq 'missing' ? return : uc($_)
        } @$_;
        \@ids;
    } @$paths;
}

$continued = 0;
@missing = implicit_ids_for_paths([ [qw(a missing)], [qw(c)] ]);
$continued = 1;
is(scalar @missing, 0,
    'return inside nested map exits a subroutine with an implicit final expression');
ok($continued, 'implicit nested map return does not escape into the caller');

sub three_level_ids {
    my ($paths) = @_;
    map {
        my @outer = map {
            my @inner = map {
                $_ eq 'missing' ? return : uc($_)
            } @$_;
            \@inner;
        } @$_;
        \@outer;
    } @$paths;
}

$continued = 0;
@missing = three_level_ids([ [ [qw(a missing)] ], [ [qw(c)] ] ]);
$continued = 1;
is(scalar @missing, 0, 'return propagates through three nested map blocks');
ok($continued, 'three-level map return stops at the owning subroutine');

{
    package NestedMapReturnObject;

    sub new { bless { ids => { a => 1 } }, shift }

    sub get_ids {
        my ($self, $paths) = @_;
        map {
            my @ids = map {
                defined $self->{ids}{$_} ? $self->{ids}{$_} : return
            } @$_;
            \@ids;
        } @$paths;
    }

    sub has_any {
        my ($self, $paths) = @_;
        return scalar($self->get_ids($paths)) ? 1 : 0;
    }
}

my $object = NestedMapReturnObject->new;
$continued = 0;
is($object->has_any([ [qw(a missing)] ]), 0,
    'return from a method map is consumed by the owning method');
$continued = 1;
ok($continued, 'method map return does not escape through method dispatch');
