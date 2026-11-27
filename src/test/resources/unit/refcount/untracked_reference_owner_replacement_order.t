use strict;
use warnings;
use Test::More tests => 2;

package Issue1649::OldSlotValue;
sub new { bless {}, shift }
sub DESTROY {
    $main::observed_slot_type = ref($main::slot) || 'undef';
}

package main;
our ($slot, $observed_slot_type);

$slot = Issue1649::OldSlotValue->new;
$slot = {};

is($observed_slot_type, 'HASH',
    'the displaced referent is destroyed after the slot contains its replacement');
is(ref($slot), 'HASH', 'the replacement remains in the scalar slot');
