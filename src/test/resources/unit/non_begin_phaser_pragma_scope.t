#!/usr/bin/env perl
use strict;
use warnings;
use Test::More tests => 3;

use integer;

END {
    my $x = 1;
}

sub integer_division_after_end {
    return 5 / 2;
}

sub local_no_integer {
    no integer;
    return 5 / 2;
}

sub integer_division_after_local_no_integer {
    return 5 / 2;
}

is(integer_division_after_end(), 2, 'END block does not clear file-scoped use integer');
is(local_no_integer(), 2.5, 'no integer remains local to its sub body');
is(integer_division_after_local_no_integer(), 2, 'sub-local no integer does not leak to later subs');
