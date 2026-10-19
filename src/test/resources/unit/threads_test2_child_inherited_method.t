use strict;
use warnings;
use threads;
use Test::More tests => 2;

my $worker = threads->create(sub {
    ok(1, 'Test2 assertion from child');
    return 7;
});

is($worker->join(), 7, 'worker joins after child test output');
