use strict;
use warnings;
use Test::More;

eval { &CORE::abs(1, 2) };
like $@, qr/^Too many arguments for abs at /,
    'CORE unary wrapper enforces its maximum arity';

is &CORE::abs(-3), 3, 'CORE unary wrapper still dispatches correctly';

eval { &CORE::atan2(1) };
like $@, qr/^Not enough arguments for atan2 at /,
    'CORE binary wrapper enforces its minimum arity';

eval { &CORE::atan2(1, 2, 3) };
like $@, qr/^Too many arguments for atan2 at /,
    'CORE binary wrapper enforces its maximum arity';

done_testing;
