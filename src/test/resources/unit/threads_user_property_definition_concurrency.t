use strict;
use warnings;
use feature 'state';
use threads;
use Test::More tests => 2;

sub InSlowSharedDefinition {
    state $which = 0;
    sleep 12 unless $which++;
    return '0042';
}

my @workers = map +threads->create(sub {
    sleep 1;
    my $property = '\p{InSlowSharedDefinition}';
    qr/$property/;
    return 1;
}), 0 .. 1;

$workers[1]->join;
my $error = $workers[1]->error;
$workers[0]->detach;

like($error,
    qr/Timeout waiting for another thread to define "InSlowSharedDefinition" in regex/,
    'same user-property definition times out without serializing native regex compilation');
ok(1, 'the blocked defining thread can be detached after the waiter terminates');
