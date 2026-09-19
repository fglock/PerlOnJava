use strict;
use warnings;
use feature 'state';
use Test::More;

sub next_value {
    state ($value) //= 3;
    return $value++;
}

is(next_value(), 3, 'parenthesized state default initializes once');
is(next_value(), 4, 'parenthesized state default persists across calls');
is(next_value(), 5, 'parenthesized state default continues incrementing');

done_testing;
