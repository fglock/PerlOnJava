use strict;
use warnings;
use Test::More tests => 1;

our $fetched_key;
{
    package EmptyListAssignmentTie;
    sub TIEHASH { bless {}, shift }
    sub FETCH { $main::fetched_key = $_[1]; return }
}

{
    package main;
    tie my %tied, 'EmptyListAssignmentTie';
    () = $tied{\'key'};
}

is(ref($fetched_key), 'SCALAR',
    'empty-list assignment does not stringify a tied hash reference key');
