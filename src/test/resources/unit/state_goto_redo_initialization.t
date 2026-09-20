use strict;
use warnings;
use feature 'state';
use Test::More;

my $vi;
{
    goto Elvis unless $vi;
    state $calvin = ++$vi;
    Elvis: state $vile = ++$vi;
    redo unless defined $calvin;

    is $calvin, 2, 'redo initializes state declaration skipped by goto';
    is $vile, 1, 'previously initialized state remains unchanged';
    is $vi, 2, 'redo re-enters the block before the label';
}

done_testing;
