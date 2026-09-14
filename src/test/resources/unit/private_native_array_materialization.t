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
for my $i (0 .. 31) {
    $generated[$i] = $generated[$i] ^ 17;
}
is($generated[0], 22, 'bounded loop reads an earlier complete initializer');
is($generated[31], 9, 'bounded loop reads every initialized carrier element');
is(scalar @generated, 32, 'bounded loop retains native array length after materialization');

my @bare;
$bare[0] = 55;
is($bare[0], 55, 'bare fresh declaration materializes correctly');

done_testing;
