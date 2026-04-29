# D-W6.4 — Weakened-hash-element drift reproducer.
#
# Hypothesis: `Class::MOP::weaken_metaclass` calls `weaken($METAS{$pkg})`
# right after `$METAS{$pkg} = $meta`. The combination "store strong ref
# in hash, then weaken it in place" is what triggers the metaclass's
# refCount to drift to 0 even though other strong holders may still
# exist.
#
# This test recreates the pattern in bare Perl.
use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken isweak);

my $destroyed = 0;
package Probe;
sub new { bless { id => ++$Probe::N }, shift }
sub DESTROY { $destroyed++ }

package main;

# ---- Pattern A: the exact %METAS shape ----------------------------------
# `our %METAS;` package global. Store strong ref, immediately weaken.
# Caller still holds the strong ref via $meta.
{
    package Registry;
    our %METAS;
    package main;
    %Registry::METAS = ();
    $destroyed = 0;

    {
        my $meta = Probe->new;
        $Registry::METAS{Foo} = $meta;
        weaken($Registry::METAS{Foo});

        # The hash slot is now a weak ref. $meta is the only strong holder.
        ok defined $Registry::METAS{Foo}, 'A: weakened slot still defined';
        is $destroyed, 0, 'A: $meta keeps Probe alive while in scope';
    }
    # $meta scope exits — should drop the only strong ref.
    is $destroyed, 1, 'A: Probe destroyed after $meta scope exits';
    ok !defined $Registry::METAS{Foo}, 'A: weak slot now undef';
}

# ---- Pattern B: $meta stored in TWO places, one weakened ----------------
# Like Class::MOP: $meta in %METAS (weakened) AND in some other strong
# holder. Should stay alive while the strong holder is live.
{
    package Registry;
    our %METAS;
    package main;
    %Registry::METAS = ();
    $destroyed = 0;

    my @keepalive;
    {
        my $meta = Probe->new;
        $Registry::METAS{Bar} = $meta;
        weaken($Registry::METAS{Bar});
        push @keepalive, $meta;       # additional strong holder
    }
    is $destroyed, 0, 'B: @keepalive preserves Probe after $meta scope exits';
    ok defined $Registry::METAS{Bar},
        'B: weak slot points to alive Probe via @keepalive';
    @keepalive = ();
    is $destroyed, 1, 'B: Probe destroyed when @keepalive is cleared';
    ok !defined $Registry::METAS{Bar}, 'B: weak slot is now undef';
}

# ---- Pattern C: many weakened entries, like %METAS during bootstrap -----
{
    package Registry;
    our %METAS;
    package main;
    %Registry::METAS = ();
    $destroyed = 0;

    my @keepalive;
    for my $i (1 .. 20) {
        my $meta = Probe->new;
        $Registry::METAS{"Pkg$i"} = $meta;
        weaken($Registry::METAS{"Pkg$i"});
        push @keepalive, $meta;
    }

    is $destroyed, 0, 'C: 20 metaclasses survive while held by @keepalive';
    my $alive = grep { defined $Registry::METAS{$_} }
                map { "Pkg$_" } 1 .. 20;
    is $alive, 20, 'C: all 20 weak slots still resolve';

    @keepalive = ();
    is $destroyed, 20, 'C: all 20 destroyed when keepalive drops';
}

# ---- Pattern D: store strong, weaken, drop my-var, recover via copy -----
# The "rescue" pattern Schema::DESTROY uses: copy the weak ref back to a
# strong ref to keep the object alive past its first DESTROY.
{
    package Registry;
    our %METAS;
    package main;
    %Registry::METAS = ();
    $destroyed = 0;

    my $rescued;
    {
        my $meta = Probe->new;
        $Registry::METAS{Baz} = $meta;
        weaken($Registry::METAS{Baz});
        # In real Schema::DESTROY, this would be inside DESTROY itself;
        # here we just probe the pattern.
        $rescued = $Registry::METAS{Baz};   # promote weak → strong
    }
    is $destroyed, 0, 'D: $rescued promotes weak ref → keeps Probe alive';
    is ref($rescued), 'Probe', 'D: rescued ref still blessed';

    $rescued = undef;
    is $destroyed, 1, 'D: Probe destroyed after $rescued released';
}

done_testing;
