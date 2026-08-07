use strict;
use warnings;
use Test::More tests => 5;

my @candidates = (86, 24);
my ($selected, $tries);
while (1) {
    $selected = shift @candidates;
    $tries++;
    die 'redo unless did not restart the loop' if $tries > 3;
    redo unless 0 < $selected && $selected <= 52;
    last;
}
is($selected, 24, 'redo unless restarts the current loop');
is($tries, 2, 'redo unless re-enters at the loop body');

my $text = '';
for my $value (1 .. 2) {
    $text .= $value;
    redo unless length($text) > 1;
}
is($text, '112', 'redo unless does not advance a for iterator');

my %seen = (initial => 1);
my @generated = (86, 24);
my $key = 'initial';
my $attempts = 0;
while ($seen{$key}++) {
    $key = shift @generated;
    $attempts++;
    die 'hash-condition redo did not restart the loop' if $attempts > 3;
    redo unless 0 < $key && $key <= 52;
}
is($key, 24, 'redo unless works in a postincrement hash-condition loop');
is($attempts, 2, 'hash-condition redo restarts without checking the condition');
