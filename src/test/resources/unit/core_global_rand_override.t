use strict;
use warnings;
use Test::More tests => 3;

use lib 'src/test/resources/unit/lib';
use CoreGlobalRandFallback;
use CoreGlobalRandConsumer;

my $fallback = CoreGlobalRandConsumer::call_rand(10);
ok $fallback >= 0 && $fallback < 10, 'rand override can call its compiled core fallback';
is $CoreGlobalRandFallback::rand_depth, 0, 'core fallback returns through the override once';

{
    local $CoreGlobalRandFallback::rand_hook = sub { 4 };
    is CoreGlobalRandConsumer::call_rand(10), 4,
        'rand compiled after installation dispatches through CORE::GLOBAL';
}
