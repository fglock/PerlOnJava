use strict;
use warnings;
use Test::More tests => 6;

{
    package HashSlotReplacementOld;
    sub DESTROY {
        $main::hash_slot_replacement_destroyed++;
        $main::hash_slot_replacement_saw_new =
            ref($main::hash_slot_replacement{slot}) eq 'HashSlotReplacementNew';
    }

    package HashSlotReplacementNew;

    package ArraySlotReplacementOld;
    sub DESTROY {
        $main::array_slot_replacement_destroyed++;
        $main::array_slot_replacement_saw_new =
            ref($main::array_slot_replacement[0]) eq 'ArraySlotReplacementNew';
    }

    package ArraySlotReplacementNew;
}

our %hash_slot_replacement;
our $hash_slot_replacement_destroyed = 0;
our $hash_slot_replacement_saw_new = 0;
our @array_slot_replacement;
our $array_slot_replacement_destroyed = 0;
our $array_slot_replacement_saw_new = 0;

$hash_slot_replacement{slot} = bless {}, 'HashSlotReplacementOld';
$hash_slot_replacement{slot} = bless {}, 'HashSlotReplacementNew';

is($hash_slot_replacement_destroyed, 1,
   'replacing the final hash owner destroys the old referent once');
ok($hash_slot_replacement_saw_new,
   'the old referent observes the published replacement from DESTROY');
is(ref($hash_slot_replacement{slot}), 'HashSlotReplacementNew',
   'the replacement remains in the hash slot');

$array_slot_replacement[0] = bless {}, 'ArraySlotReplacementOld';
$array_slot_replacement[0] = bless {}, 'ArraySlotReplacementNew';

is($array_slot_replacement_destroyed, 1,
   'replacing the final array owner destroys the old referent once');
ok($array_slot_replacement_saw_new,
   'the old array referent observes the published replacement from DESTROY');
is(ref($array_slot_replacement[0]), 'ArraySlotReplacementNew',
   'the replacement remains in the array slot');
