use strict;
use warnings;
use threads;
use Test::More;

my $count = 0;
my $events = [];
my $shared = qr/(?{
    ++$count;
    push @$events, $count;
})A/;

my $left = qr/^$shared$/;
my $right = qr/^$shared$/;

ok('A' =~ $left, 'parent warms first qr wrapper');
is_deeply([$count, $events], [1, [1]], 'parent callback state is warm');

my $first_child = threads->create(sub {
    my $left_ok = 'A' =~ $left;
    my $right_ok = 'A' =~ $right;
    return [$left_ok && $right_ok, $count, [@$events]];
})->join;

is_deeply($first_child, [1, 3, [1, 2, 3]],
    'two qr roots share one child-side callback graph');
is_deeply([$count, $events], [1, [1]],
    'shared child callback graph remains isolated from parent');

my $second_child = threads->create(sub {
    my $right_ok = 'A' =~ $right;
    my $left_ok = 'A' =~ $left;
    return [$right_ok && $left_ok, $count, [@$events]];
})->join;

is_deeply($second_child, [1, 3, [1, 2, 3]],
    'repeated child recreates one shared callback graph');
is_deeply([$count, $events], [1, [1]],
    'repeated shared-wrapper clone leaves parent isolated');

ok('A' =~ $right, 'parent second qr wrapper remains reusable');
is_deeply([$count, $events], [2, [1, 2]],
    'parent qr roots retain their shared callback state');

done_testing;
