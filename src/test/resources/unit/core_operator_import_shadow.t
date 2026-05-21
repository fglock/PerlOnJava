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

done_testing();
