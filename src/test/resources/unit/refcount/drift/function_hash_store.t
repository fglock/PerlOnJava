# D-W6.4 — Function-internal hash store reproducer.
#
# `Class::MOP::store_metaclass_by_name($pkg, $meta)` does
# `$METAS{$pkg} = $meta` from inside a sub. The argument `$meta` is
# accessed as `$_[1]` (or after a `my $self = shift; my $meta = shift`).
# We hypothesised this path may skip a refCount increment that the
# bare-script `$h{key} = $obj` path exercises correctly.
#
# Patterns covered:
#   A. Direct `sub setit { $H{$_[0]} = $_[1] }` form (Class::MOP shape).
#   B. With `my $key = shift; my $val = shift;` form.
#   C. Package-global hash, sub in same package.
#   D. Many calls in a loop (mimics %METAS being filled during bootstrap).
use strict;
use warnings;
use Test::More;

my $destroyed = 0;
package Probe;
sub new { bless { id => ++$Probe::N }, shift }
sub DESTROY { $destroyed++ }

package main;

# ---- Pattern A: direct $_[N] indexing in setter sub ----------------------
{
    $destroyed = 0;
    my %H;
    sub setit_A { $H{$_[0]} = $_[1] }

    {
        my $obj = Probe->new;
        setit_A('Foo', $obj);
        # $obj scope ends here. %H still holds it.
    }
    is $destroyed, 0, 'A: setter using $_[N] keeps Probe alive';
    ok defined $H{Foo}, 'A: hash slot defined';
    ok defined $H{Foo}{id}, 'A: slot has data';
    %H = ();
    is $destroyed, 1, 'A: destroyed after %H cleared';
}

# ---- Pattern B: shift into my-vars in setter sub -------------------------
{
    $destroyed = 0;
    my %H;
    sub setit_B {
        my $key = shift;
        my $val = shift;
        $H{$key} = $val;
    }

    {
        my $obj = Probe->new;
        setit_B('Bar', $obj);
    }
    is $destroyed, 0, 'B: setter using shift keeps Probe alive';
    ok defined $H{Bar}, 'B: hash slot defined';
    %H = ();
    is $destroyed, 1, 'B: destroyed after %H cleared';
}

# ---- Pattern C: package-global hash, setter in same package -------------
# This is the EXACT shape Class::MOP uses (`our %METAS` + `sub store_*`).
{
    package Registry;
    our %METAS;
    sub store_meta { $METAS{$_[0]} = $_[1] }
    sub get_meta   { $METAS{$_[0]} }

    package main;
    %Registry::METAS = ();
    $destroyed = 0;

    {
        my $obj = Probe->new;
        Registry::store_meta('Pkg', $obj);
    }
    is $destroyed, 0,
        'C: package-global hash via setter sub keeps Probe alive';
    ok defined Registry::get_meta('Pkg'), 'C: slot still resolvable';
    %Registry::METAS = ();
    is $destroyed, 1, 'C: destroyed after clear';
}

# ---- Pattern D: many calls in a loop (mimics bootstrap) ------------------
{
    package Reg2;
    our %METAS;
    sub store_meta { $METAS{$_[0]} = $_[1] }

    package main;
    %Reg2::METAS = ();
    $destroyed = 0;

    for my $i (1 .. 20) {
        my $obj = Probe->new;
        Reg2::store_meta("Pkg$i", $obj);
    }
    is $destroyed, 0, 'D: 20 setter calls keep all 20 Probes alive';
    my $alive = scalar grep { defined $Reg2::METAS{$_} }
                       map { "Pkg$_" } 1 .. 20;
    is $alive, 20, 'D: all 20 slots resolve';
    %Reg2::METAS = ();
    is $destroyed, 20, 'D: all 20 destroyed after clear';
}

# ---- Pattern E: setter that returns the value (Class::MOP shape) --------
# `sub store_metaclass_by_name { $METAS{$_[0]} = $_[1] }` — note the
# implicit return of the assignment value.
{
    package Reg3;
    our %METAS;
    sub store { $METAS{$_[0]} = $_[1] }

    package main;
    %Reg3::METAS = ();
    $destroyed = 0;

    {
        my $obj = Probe->new;
        my $stored = Reg3::store('X', $obj);
        # $stored is now another strong holder.
        $obj = undef;
        is $destroyed, 0, 'E: $stored holds the Probe alive';
        $stored = undef;
        is $destroyed, 0, 'E: %Reg3::METAS still holds the Probe';
    }
    ok defined $Reg3::METAS{X}, 'E: slot still resolvable after locals dropped';
    is $destroyed, 0, 'E: hash global keeps it alive';
    %Reg3::METAS = ();
    is $destroyed, 1, 'E: destroyed only after hash cleared';
}

done_testing;
