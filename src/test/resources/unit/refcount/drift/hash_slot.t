# D-W6.2 — Hash-slot refCount drift reproducer.
#
# `RuntimeHash.put` does a plain HashMap.put without any refCount
# tracking. So when we do `$h{key} = $obj`:
#   - $obj's referent's refCount is NOT incremented (the hash doesn't
#     register as an owner)
#   - the previous slot value's referent's refCount is NOT decremented
#
# When the right-hand-side scalar's scope exits, refCount drops to 0
# even though the hash still strongly holds the value, and DESTROY
# fires on a live object.
#
# This is the core drift behind the Class::MOP failure: `our %METAS`
# stores metaclass instances, but their refCount goes to 0 the moment
# the my-var that built them goes out of scope.
use strict;
use warnings;
use Test::More;

my $destroyed = 0;
package Probe;
sub new { bless { id => ++$Probe::N }, shift }
sub DESTROY { $destroyed++ }

package main;

# ---- Pattern A: direct hash slot ----------------------------------------
# Place a blessed object in a hash slot, drop the lexical, expect the
# object to live (held by the hash).
{
    $destroyed = 0;
    my %h;
    {
        my $obj = Probe->new;
        $h{key} = $obj;
        # $obj scope ends here — but %h still holds a strong ref
    }
    is $destroyed, 0, 'A: hash-held blessed object survives my-var exit';
    ok defined $h{key}, 'A: hash slot still defined';
    ok defined $h{key}{id}, 'A: hash slot still has data';
    %h = ();   # explicit clear
    is $destroyed, 1, 'A: blessed object destroyed after hash cleared';
}

# ---- Pattern B: package-global hash (the %METAS shape) -------------------
# The exact shape Class::MOP uses for `our %METAS`.
{
    $destroyed = 0;
    {
        package Registry;
        our %METAS;
        package main;
        my $obj = Probe->new;
        $Registry::METAS{Foo} = $obj;
    }
    is $destroyed, 0,
        'B: package-global hash holds blessed object after my-var exit';
    ok defined $Registry::METAS{Foo}, 'B: registered slot still defined';
    %Registry::METAS = ();
    is $destroyed, 1, 'B: blessed object destroyed after %METAS cleared';
}

# ---- Pattern C: many objects in a hash ----------------------------------
{
    $destroyed = 0;
    my %registry;
    for my $i (1 .. 50) {
        $registry{$i} = Probe->new;
    }
    is scalar(keys %registry), 50, 'C: 50 entries in registry';
    is $destroyed, 0, 'C: no premature destroys';

    my $live_count = 0;
    for my $k (keys %registry) {
        $live_count++ if defined $registry{$k} && defined $registry{$k}{id};
    }
    is $live_count, 50, 'C: all 50 live with valid {id}';

    %registry = ();
    is $destroyed, 50, 'C: all 50 destroyed after clear';
}

# ---- Pattern D: replace then drop ---------------------------------------
# Slot overwrite must release the OLD value (Perl 5 refcount semantics).
{
    $destroyed = 0;
    my %h;
    $h{key} = Probe->new;     # obj1
    is $destroyed, 0, 'D: obj1 alive after install';
    $h{key} = Probe->new;     # obj2 — obj1 should be destroyed
    is $destroyed, 1, 'D: obj1 destroyed when slot overwritten';
    %h = ();
    is $destroyed, 2, 'D: obj2 destroyed after clear';
}

done_testing;
