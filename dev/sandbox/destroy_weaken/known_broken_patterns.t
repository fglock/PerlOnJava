#!/usr/bin/env perl
# Known-broken patterns that Phase 1-5 of refcount_alignment_plan.md should fix.
# This test file is currently EXPECTED TO FAIL on jperl. Success = all pass
# on both backends.
#
# See dev/design/refcount_alignment_plan.md.

use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);

# =============================================================================
# Pattern 1: DESTROY resurrection via captured strong ref
# DBIC t/storage/txn_scope_guard.t test 18 depends on this.
# =============================================================================
{
    package Resurrectable;
    my $destroy_count = 0;
    sub new { bless { id => $_[1] }, $_[0] }
    sub DESTROY {
        my $self = shift;
        $destroy_count++;
    }
    sub count { return $destroy_count }
    sub reset_count { $destroy_count = 0 }
}

Resurrectable::reset_count();
my @kept;
{
    my $obj = Resurrectable->new(1);

    # __WARN__ handler that captures @DB::args of each caller frame
    local $SIG{__WARN__} = sub {
        package DB;
        my $fr;
        while (my @f = caller(++$fr)) {
            push @kept, @DB::args;
        }
    };

    # Wrap in a sub so there's a frame whose args include $obj
    my $trigger = sub { warn "trigger\n" };
    $trigger->($obj);

    undef $obj;
    # At this point in native perl, @kept should still hold $obj,
    # keeping DESTROY from firing yet.
}
# On native perl, DESTROY may fire 0 or 1 times here (depends on whether
# $trigger's frame's @_ was captured before or after $obj lost its name).
my $count_after_undef = Resurrectable::count();

@kept = ();
# Now all captured refs are gone. If DESTROY hasn't fired yet, it fires now.
my $count_after_clear = Resurrectable::count();

ok($count_after_clear >= 1, "DESTROY fires at least once when last ref dropped (got $count_after_clear)");

# =============================================================================
# Pattern 2: Parent anonymous hash with inflated refCount does not cascade
# DBIC t/52leaks.t tests 12-18 depend on this.
# =============================================================================
sub inflate_refcount_via_call {
    my ($thing) = @_;  # This temporary may leak refs
    return length(ref($thing));
}

{
    my $child;
    {
        my $parent = { child_arr => [1, 2, 3] };
        $child = $parent->{child_arr};
        weaken($child);
        # Inflate parent's refCount via a call (mimics visit_refs pattern)
        for (1..5) {
            inflate_refcount_via_call($parent);
        }
    }
    # $parent scope exited. In Perl, $child should now be undef.
    ok(!defined $child, "weak ref to child array cleared after parent scope exit");
}

# =============================================================================
# Pattern 3: `my $self = shift` inside DESTROY doesn't leak refCount
# Required for Phase 3 DESTROY FSM.
# =============================================================================
{
    package MyShiftObj;
    our $destroy_count = 0;
    our $destroy_refcnt_inside;
    sub new { bless { id => $_[1] }, $_[0] }
    sub DESTROY {
        my $self = shift;
        $destroy_count++;
        # Capture the refcount while inside DESTROY
        $destroy_refcnt_inside = B::svref_2object(\$self)->REFCNT
            if defined &B::svref_2object;
    }
}

require B;
$MyShiftObj::destroy_count = 0;
{
    my $o = MyShiftObj->new(42);
}
is($MyShiftObj::destroy_count, 1, "DESTROY fires exactly once per lifecycle (got $MyShiftObj::destroy_count)");

# =============================================================================
# Pattern 4: Nested anonymous hash in call-arg doesn't leak children
# =============================================================================
sub consume_and_drop { my $h = shift; return scalar(keys %$h) }

{
    my $weak_inner;
    {
        consume_and_drop({ inner => [ my $arr = [1,2,3] ] });
        $weak_inner = $arr;
        weaken($weak_inner);
    }
    ok(!defined $weak_inner, "inner ARRAY collected after consume_and_drop returns");
}

done_testing();
