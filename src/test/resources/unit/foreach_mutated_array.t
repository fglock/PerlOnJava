use strict;
use warnings;
use Test::More tests => 4;

my @direct = 1 .. 16;
my @direct_seen;
for my $value (@direct) {
    push @direct_seen, $value;
    shift @direct;
}
is_deeply(\@direct_seen, [ 1, 3, 5, 7, 9, 11, 13, 15 ],
    'direct foreach follows a structurally mutated array');
is_deeply(\@direct, [ 9 .. 16 ], 'direct iteration leaves the expected tail');

my @copied = 1 .. 16;
my @copied_seen;
for my $value ((), @copied) {
    push @copied_seen, $value;
    shift @copied;
}
is_deeply(\@copied_seen, [ 1 .. 16 ],
    'leading empty list forces a structural iteration-list copy');
is_deeply(\@copied, [], 'copied iteration can consume the original array completely');
