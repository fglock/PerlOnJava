use strict;
use warnings;
use feature 'state';
use threads;
use threads::shared;
use Test::More tests => 2;

my $definition_entered :shared = 0;

sub InSlowSharedDefinition {
    {
        lock($definition_entered);
        $definition_entered = 1;
        cond_broadcast($definition_entered);
    }
    sleep 12;
    return '0042';
}

my $owner = threads->create(sub {
    my $property = '\p{InSlowSharedDefinition}';
    qr/$property/;
    return 1;
});

{
    lock($definition_entered);
    cond_wait($definition_entered) until $definition_entered;
}

my $waiter = threads->create(sub {
    my $property = '\p{InSlowSharedDefinition}';
    qr/$property/;
    return 1;
});

$waiter->join;
my $error = $waiter->error;
$owner->detach;

like($error,
    qr/Timeout waiting for another thread to define "InSlowSharedDefinition" in regex/,
    'same user-property definition times out without serializing native regex compilation');
ok(1, 'the blocked defining thread can be detached after the waiter terminates');
