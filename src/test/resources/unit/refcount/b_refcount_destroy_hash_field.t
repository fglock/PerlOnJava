#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;
use B qw(svref_2object);

{
    package BRefcountDestroyHashField;

    our $observed;

    sub new {
        my $source = bless {}, 'BRefcountDestroyHashField::Source';
        return bless { sources => { Artist => $source } }, shift;
    }

    sub DESTROY {
        my ($self) = @_;
        $observed = B::svref_2object($self->{sources}{Artist})->REFCNT;
    }
}

{
    my $schema = BRefcountDestroyHashField->new;
    undef $schema;
}

is($BRefcountDestroyHashField::observed, 1,
    'B reports one owner for a source held only by a DESTROYing hash field');

done_testing;
