use strict;
use warnings;
use feature 'class';
no warnings 'experimental::class';
use Test::More;

class ConditionalDefaults {
    field $exists  :param(e)   = 'exists-default';
    field $defined :param(d) //= 'defined-default';
    field $truthy  :param(t) ||= 'truthy-default';

    method values { return ($exists, $defined, $truthy) }
}

is_deeply [ConditionalDefaults->new(d => 'yes', t => 'yes')->values],
    ['exists-default', 'yes', 'yes'], 'truthy arguments are preserved';
is_deeply [ConditionalDefaults->new(e => 0, d => 0, t => 0)->values],
    [0, 0, 'truthy-default'], 'defined and truthy defaults preserve their distinct conditions';
is_deeply [ConditionalDefaults->new(e => undef, d => undef, t => undef)->values],
    [undef, 'defined-default', 'truthy-default'], 'undefined arguments receive both defaults';

done_testing;
