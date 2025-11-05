use feature 'say';
use strict;
use Test::More;
use warnings;

# Test simple `next` in a `for` loop
my @array = (1, 2, 3, 4);
my $printed = 0;
for my $i (@array) {
    next if $i == 2;
    $printed = 1 if $i == 2;
}
ok(!$printed, 'Simple `next` in `for` loop');

# Test `next LABEL` in a nested `for` loop  
my $counter = 0;
OUTER_LOOP: for my $x (1..3) {
    INNER_LOOP: for my $y (1..5) {
        $counter++;
        next OUTER_LOOP if $y == 3;
    }
}
ok(!($counter != 9), '`next LABEL` in nested `for` loop');

done_testing();
