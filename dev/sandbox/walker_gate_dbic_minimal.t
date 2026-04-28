# Minimal failing reproducer for the walker-gate DBIC regression.
# Move from dev/sandbox/ to src/test/resources/unit/refcount/ once it
# passes (i.e. once the underlying refcount/walker bug is fixed).
#
# IMPORTANT: this test deliberately uses raw `print` with TAP rather than
# `use Test::More`. Loading Test::More creates enough additional globals
# / lexicals to mask the bug — the walker's reachable set ends up large
# enough that the @objs my-array is reachable transitively. The bare
# version below reliably fails the walker_gate_dbic_minimal.t pattern.
#
# Pattern:
#   - 5 blessed objects in @objs (top-level my array)
#   - 5 wrappers in @wrappers (top-level my array)
#   - cycle:  $obj->{wrapper} = $w (strong)
#   - back-ref: weaken($w->{obj}) (weak)
#   - many subsequent ref operations to trigger mortal flushes / auto-sweep
#
# Expected: all 5 wrappers should still see their obj.
# Observed (Apr 2026): wrapper #1 (and sometimes others) have undef
# ->{obj} because T::Obj id=1 was DESTROYED by maybeAutoSweep →
# sweepWeakRefs even though @objs still holds it. Same shape of bug as
# DBIC's "source 'Track' is not associated with a schema" failure.
#
# Stack trace from PJ_DESTROY_TRACE=1 confirms:
#   at ReachabilityWalker.sweepWeakRefs(...)
#   at MortalList.maybeAutoSweep(...)
#   at MortalList.flush(...)
#
# The auto-sweep's `walk()` pass produces a `live` set that does NOT
# include @objs's elements, so they are flagged unreachable and DESTROY'd
# even though the named lexical @objs is still in scope.
#
# Root cause hypothesis: `ReachabilityWalker.walk()` seeds from globals
# and ScalarRefRegistry, but the @objs my-array isn't directly seeded.
# The fix likely requires seeding from `MyVarCleanupStack.snapshotLiveVars()`
# in walk() the same way `isReachableFromRoots()` already does.

use strict;
use warnings;
use Scalar::Util qw(weaken);

package T::Obj;
my $count = 0;
sub new { my $c = shift; bless { id => ++$count }, $c }
sub id { $_[0]->{id} }
sub DESTROY { $main::DESTROYED{$_[0]->{id}} = 1 }

package T::Wrapper;
use Scalar::Util qw(weaken);
sub new {
    my ($class, $obj) = @_;
    my $self = bless { obj => $obj }, $class;
    weaken($self->{obj});
    $self;
}
sub get {
    my $self = shift;
    die "no obj" unless defined $self->{obj};
    $self->{obj};
}

package main;

%main::DESTROYED = ();

my @objs;
my @wrappers;
for (1..5) {
    my $o = T::Obj->new;
    my $w = T::Wrapper->new($o);
    $o->{wrapper} = $w;
    push @objs, $o;
    push @wrappers, $w;
}

my $failed = 0;
for my $iter (1..20) {
    for my $w (@wrappers) {
        my $o = eval { $w->get };
        unless (defined $o) {
            $failed++;
            next;
        }
        my $id = $o->id;
        my @temps = (\$id, [$o], { id => $id });
    }
}

# Bare TAP — no Test::More to keep the walker's reachable set small.
my @tests;
push @tests, ['no premature DESTROYs', 0 == scalar keys %main::DESTROYED,
    "destroyed ids: " . join(",", sort { $a <=> $b } keys %main::DESTROYED)];
push @tests, ['no get() failures',     $failed == 0,  "$failed get() failures"];
my $attached = scalar grep { defined $_->{obj} } @wrappers;
push @tests, ['all 5 wrappers attached', $attached == 5, "only $attached/5 attached"];
my $with_wrapper = scalar grep { $_->{wrapper} } @objs;
push @tests, ['all 5 objs have wrapper', $with_wrapper == 5, "only $with_wrapper/5 with wrapper"];

print "1..", scalar @tests, "\n";
for my $i (0..$#tests) {
    my ($desc, $ok, $diag) = @{$tests[$i]};
    my $n = $i + 1;
    if ($ok) {
        print "ok $n - $desc\n";
    } else {
        print "not ok $n - $desc\n";
        print "# $diag\n";
    }
}

exit(scalar grep { !$_->[1] } @tests);
