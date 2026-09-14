use strict;
use warnings;
use Test::More;

my @words = ();
$words[0] = 42;
$words[1] = $words[0] ^ 7;

my $first_word = \$words[0];
is($$first_word, 42, 'element reference observes materialized earlier word');

my $index = 1;
$words[$index] = 123;
is($words[1], 123, 'dynamic-index write stays on the ordinary array after materialization');
is(scalar @words, 2, 'materialization retains array length');

my @generated = ();
for my $i (0 .. 31) {
    $generated[$i] = $i ^ 7;
}
is($generated[0], 7, 'bounded loop initializes the first native word');
is($generated[31], 24, 'bounded loop initializes the final native word');
is(scalar @generated, 32, 'bounded loop retains native array length after materialization');

done_testing;
