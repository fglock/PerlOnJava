#!/usr/bin/env perl
use strict;
use warnings;
use Test::More tests => 1;

package Local::EmptyListAssignment;
sub TIESCALAR { bless {}, shift }
sub FETCH { $_[0]{fetched}++ }
sub empty { }

package main;
tie my $tied, 'Local::EmptyListAssignment';
() = (Local::EmptyListAssignment::empty(), ($tied) x 10);
is(tied($tied)->{fetched}, undef,
    'void assignment to an empty list does not fetch discarded tied values');
