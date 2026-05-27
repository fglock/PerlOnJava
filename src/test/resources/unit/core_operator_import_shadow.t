use strict;
use warnings;
use Test::More;

use subs 'atan2';

sub atan2 {
    return join ":", "shadow", @_;
}

is(atan2(1, 2), 'shadow:1:2', 'use subs can shadow CORE atan2');

my $core = CORE::atan2(1, 1);
ok(abs($core - 0.785398163397448) < 0.0001, 'CORE::atan2 still calls the builtin');

{
    use subs 'log';
    sub log {
        return join ":", "shadow", @_;
    }

    is(log(30, "warnmsg"), 'shadow:30:warnmsg', 'use subs can shadow CORE log');
    ok(abs(CORE::log(exp(1)) - 1) < 0.0001, 'CORE::log still calls the builtin');
}

done_testing();
