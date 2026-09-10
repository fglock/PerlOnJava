use strict;
use warnings;
use Test::More;

sub called_from_labeled_loop { 1 }

sub exits_labeled_outer_loop {
    OUTER: while (1) {
        for (1 .. 4) {
            called_from_labeled_loop();
            last OUTER;
        }
    }
    return 42;
}

is(exits_labeled_outer_loop(), 42,
   'a call in a nested loop can precede last on an outer label');

done_testing;
