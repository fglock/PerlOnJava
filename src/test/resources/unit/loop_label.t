use feature 'say';
use strict;
use Test::More;
use warnings;

###################
# Perl `next` Tests

# Test simple `next` in a `for` loop
my @array = (1, 2, 3, 4);
my $printed = 0;
for my $i (@array) {
    next if $i == 2;  # Skip when $i is 2
    $printed = 1 if $i == 2;  # This shouldn't execute because $i == 2 is skipped
}
ok(!$printed, 'Simple `next` in `for` loop');

# Test `next LABEL` in a nested `for` loop
my $counter = 0;
OUTER_LOOP: for my $x (1..3) {
    INNER_LOOP: for my $y (1..5) {
        $counter++;
        next OUTER_LOOP if $y == 3;  # Skip outer loop when inner loop counter is 3
    }
}
ok(!($counter != 9), '`next LABEL` in nested `for` loop <$counter>');

###################
# Perl `redo` Tests

# Test simple `redo` in a `for` loop
my $redo_count = 0;
for my $i (1..3) {
    $redo_count++;
    redo if $redo_count == 2;  # Re-execute loop when count is 2, no increment
    last if $redo_count == 3;  # Exit loop when count reaches 3
}
ok(!($redo_count != 3), 'Simple `redo` in `for` loop');

###################
# Perl `last` Tests

# Test simple `last` in a `while` loop
my $last_i = 0;
while ($last_i < 5) {
    $last_i++;
    last if $last_i == 3;  # Break the loop when $last_i is 3
}
ok(!($last_i != 3), 'Simple `last` in `while` loop');

# Test `last LABEL` in a nested loop
my $last_count = 0;
OUTER_LOOP: for my $a (1..3) {
    INNER_LOOP: for my $b (1..5) {
        $last_count++;
        last OUTER_LOOP if $b == 2;  # Exit both loops when $b is 2
    }
}
ok(!($last_count != 2), '`last LABEL` in nested loop');

done_testing();
