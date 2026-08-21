use strict;
use warnings;
use threads;
use Test::More;

my $pattern = qr/(?{ 1 })(??{ 'A' })/;

ok('A' =~ $pattern, 'parent warms callback and dynamic regex');

my $worker = threads->create(sub {
    my $first = 'A' =~ $pattern;
    my $second = 'A' =~ $pattern;
    my $reject = 'B' !~ $pattern;
    return $first && $second && $reject;
});

ok($worker->join,
    'child reuses the warmed callback and dynamic regex without recompilation drift');
ok('A' =~ $pattern, 'parent regex remains reusable after child completion');
ok('B' !~ $pattern, 'parent regex retains its dynamic match program');

done_testing;
