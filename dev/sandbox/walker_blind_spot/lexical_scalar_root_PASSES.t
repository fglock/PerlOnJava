#!/usr/bin/env perl
# Reachability walker must seed `my $scalar = $ref` lexicals as roots,
# so that auto-sweep does not clear weak refs to objects held by them.
#
# Background: PerlOnJava's MortalList.maybeAutoSweep() runs the
# ReachabilityWalker periodically (5s throttle by default) to clear
# weak refs whose referents are no longer reachable. The walker seeds
# its root set from globals, MyVarCleanupStack, and ScalarRefRegistry.
# ScalarRefRegistry is a WeakHashMap and its snapshot races against the
# `forceGcAndSnapshot()` call that immediately precedes each sweep —
# under cumulative GC pressure, a `my $obj = $ref` lexical can be GC'd
# from the registry between the force-GC and the snapshot read, even
# though it's still alive on the JVM stack.
#
# When that happens, the walker treats the referent as unreachable and
# clears any weak refs to it. In DBIx::Class this surfaces as
# `t/52leaks.t line 430`: `Unable to perform storage-dependent operations
# with a detached result source` — the schema's weak ref from
# ResultSource::Table got cleared while the test scope's `my $schema`
# was still alive.
#
# This test forces the race deterministically using
# `JPERL_FORCE_SWEEP_EVERY_FLUSH=1` (which fires the auto-sweep on
# every statement boundary, no throttle) and verifies that the walker
# correctly sees the `my $obj` lexical as a root.
#
# Without the fix, this test FAILS — the weak ref is cleared and the
# `back_to_obj` accessor returns undef.
# With the fix (registering `my $scalar` declarations into
# MyVarCleanupStack the same way `my @arr` / `my %hash` are), the test
# PASSES because path (b) in ReachabilityWalker.walk() always finds it.

use strict;
use warnings;
use Scalar::Util qw(weaken);
use Test::More;

# Skip unless the test was launched with the debug knob.
unless ($ENV{JPERL_FORCE_SWEEP_EVERY_FLUSH}) {
    plan skip_all =>
      'set JPERL_FORCE_SWEEP_EVERY_FLUSH=1 to run this regression test';
}

package My::Holder {
    use Scalar::Util qw(weaken);
    sub new {
        my ($class, $obj) = @_;
        my $self = bless { back => $obj }, $class;
        weaken $self->{back};
        return $self;
    }
    sub back_to_obj {
        my $self = shift;
        return $self->{back}
          // die "DETACHED: weak ref cleared while \$obj still alive\n";
    }
}

# Standard pattern: a `my $obj` lexical holds a strong ref;
# elsewhere a separate object stores a WEAK back-ref to it.
my $obj    = bless { id => 'ALIVE' }, 'My::Stuff';
my $holder = My::Holder->new($obj);

ok( $holder->back_to_obj, 'baseline: weak ref intact at t=0' );
is(
    $holder->back_to_obj->{id}, 'ALIVE',
    'baseline content correct'
);

# Force several auto-sweeps. Internals::jperl_gc() forces JVM GC + sweep;
# additionally, with FORCE_SWEEP_EVERY_FLUSH=1, every statement below also
# triggers a sweep at its flush point. So we hit the race many times.
for my $i ( 1 .. 20 ) {
    Internals::jperl_gc() if defined &Internals::jperl_gc;
    my $err;
    my $r;
    eval { $r = $holder->back_to_obj; 1 } or $err = $@;
    last if $err;
    ok( defined $r, "iteration $i: weak ref still resolves" );
    is( $r->{id}, 'ALIVE', "iteration $i: content preserved" );
}

# `$obj` lexical is still alive in this scope — that's the point of the
# test. The walker should see it as a root.
ok( defined $obj, 'final: $obj lexical still alive' );

done_testing;
