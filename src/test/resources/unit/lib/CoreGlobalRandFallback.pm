package CoreGlobalRandFallback;

use strict;
use warnings;

our ($rand_hook, $rand_depth);

no warnings 'once';
*CORE::GLOBAL::rand = sub {
    die "CORE::GLOBAL::rand fallback recursed\n" if ++$rand_depth > 1;
    my $value = $rand_hook ? $rand_hook->($_[0]) : rand $_[0];
    --$rand_depth;
    return $value;
};

1;
