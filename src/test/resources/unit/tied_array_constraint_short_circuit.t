use strict;
use warnings;

use Test::More tests => 3;
use Tie::Array;

{
    package ConstraintArrayTie;
    our @ISA = ('Tie::Array');

    sub TIEARRAY { bless { data => [] }, shift }
    sub FETCH { $_[0]{data}[$_[1]] }
    sub FETCHSIZE { scalar @{ $_[0]{data} } }
    sub STORE { $_[0]{data}[$_[1]] = $_[2] }
    sub STORESIZE { $#{ $_[0]{data} } = $_[1] - 1 }
}

sub all_integers {
    my ($array) = @_;
    /^-?[0-9]+\z/ || return 0 for @$array;
    return 1;
}

{
    my @values;
    tie @values, 'ConstraintArrayTie';
    @values = (1 .. 5);
    ok(all_integers(\@values), 'constraint loop accepts a tied array');
}

{
    my @values;
    tie @values, 'ConstraintArrayTie';
    @values = ('bad', 1 .. 5);
    ok(!all_integers(\@values),
        'constraint loop can return early from a tied array');
    is($values[0], 'bad', 'tied array handler remains usable after early return');
}
