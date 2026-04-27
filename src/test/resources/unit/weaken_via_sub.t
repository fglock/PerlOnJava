use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken isweak);

# Regression test for a PerlOnJava core bug surfaced during Phase D
# of the Moose port: weaken() on a hash slot inside a sub was
# collapsing the target to undef immediately, even when other strong
# references existed in the caller's scope.
#
# Class::MOP::Attribute::attach_to_class uses exactly this pattern
# (`weaken($self->{associated_class} = $class)` inside a sub), called
# for every attribute during Class::MOP.pm's self-bootstrap. Without
# the fix, `use Class::MOP;` itself died.
#
# See dev/modules/moose_support.md, "Plan: fix the weaken bug in
# PerlOnJava core" (Step W2).

# ---------------------------------------------------------------------------
# Case 1: minimal repro — three iterations, target held by a hash in
# the caller. All three weakened slots must remain defined because
# %REG keeps the target alive.
# ---------------------------------------------------------------------------

{
    my $m = bless { tag => 'M' }, 'M';
    my %REG = (x => $m);

    sub _attach1 {
        my ($attr, $class) = @_;
        $attr->{ac} = $class;
        weaken($attr->{ac});
    }

    my @arr = ({}, {}, {});
    for my $attr (@arr) {
        _attach1($attr, $REG{x});
    }

    is(ref($arr[0]{ac}), 'M', 'iter 0: weak ref to target held by %REG stays defined');
    is(ref($arr[1]{ac}), 'M', 'iter 1: weak ref to target held by %REG stays defined');
    is(ref($arr[2]{ac}), 'M', 'iter 2: weak ref to target held by %REG stays defined');

    ok(isweak($arr[0]{ac}), 'iter 0: slot is actually a weak ref');
    ok(isweak($arr[1]{ac}), 'iter 1: slot is actually a weak ref');
    ok(isweak($arr[2]{ac}), 'iter 2: slot is actually a weak ref');
}

# ---------------------------------------------------------------------------
# Case 2: single attach inside a sub (no loop).
# Isolates the "weaken in sub" case from the "weaken in loop" case.
# ---------------------------------------------------------------------------

{
    my $m = bless { tag => 'M' }, 'M';
    my %REG = (x => $m);

    sub _attach2 {
        my ($attr, $class) = @_;
        $attr->{ac} = $class;
        weaken($attr->{ac});
    }

    my $a1 = {};
    _attach2($a1, $REG{x});

    is(ref($a1->{ac}), 'M', 'single attach in sub: weak ref stays defined');
    ok(isweak($a1->{ac}), 'single attach in sub: slot is weak');
}

# ---------------------------------------------------------------------------
# Case 3: three separate calls (not a loop). Isolates loop interaction.
# ---------------------------------------------------------------------------

{
    my $m = bless { tag => 'M' }, 'M';
    my %REG = (x => $m);

    sub _attach3 {
        my ($attr, $class) = @_;
        $attr->{ac} = $class;
        weaken($attr->{ac});
    }

    my $a1 = {}; _attach3($a1, $REG{x});
    my $a2 = {}; _attach3($a2, $REG{x});
    my $a3 = {}; _attach3($a3, $REG{x});

    is(ref($a1->{ac}), 'M', 'three calls: a1 weak ref stays defined');
    is(ref($a2->{ac}), 'M', 'three calls: a2 weak ref stays defined');
    is(ref($a3->{ac}), 'M', 'three calls: a3 weak ref stays defined');
}

# ---------------------------------------------------------------------------
# Case 4: same shape WITHOUT weaken — must remain strong refs (sanity:
# confirms the fix doesn't break normal refcounting).
# ---------------------------------------------------------------------------

{
    my $m = bless { tag => 'M' }, 'M';
    my %REG = (x => $m);

    sub _attach4 {
        my ($attr, $class) = @_;
        $attr->{ac} = $class;
        # NO weaken
    }

    my @arr = ({}, {}, {});
    for my $attr (@arr) {
        _attach4($attr, $REG{x});
    }

    is(ref($arr[0]{ac}), 'M', 'no weaken: iter 0 strong ref defined');
    is(ref($arr[1]{ac}), 'M', 'no weaken: iter 1 strong ref defined');
    is(ref($arr[2]{ac}), 'M', 'no weaken: iter 2 strong ref defined');

    ok(!isweak($arr[0]{ac}), 'no weaken: iter 0 is NOT weak');
    ok(!isweak($arr[1]{ac}), 'no weaken: iter 1 is NOT weak');
}

# ---------------------------------------------------------------------------
# Case 5: target NOT held outside the sub — weak ref should become
# undef when the sub returns and the local strong ref goes out of
# scope. Confirms weak-ref clearing still works for the actual
# "no other strong refs" case.
# ---------------------------------------------------------------------------

{
    sub _attach5 {
        my ($attr) = @_;
        my $local = bless { tag => 'fresh' }, 'M';
        $attr->{ac} = $local;
        weaken($attr->{ac});
        # $local goes out of scope on return — weak ref should clear.
    }

    my $a = {};
    _attach5($a);

    ok(!defined $a->{ac},
        'no other strong ref: weak ref clears when sub returns');
}

# ---------------------------------------------------------------------------
# Case 6: assignment via list copy from @_ followed by weaken on
# something else. Pattern from Class::MOP::Attribute.
# ---------------------------------------------------------------------------

{
    my $m = bless {}, 'M';
    my %REG = (x => $m);

    sub _attach6 {
        my ($self, $class) = @_;
        # Same line as Class::MOP::Attribute::attach_to_class
        weaken($self->{associated_class} = $class);
    }

    my @attrs = ({}, {}, {});
    for my $attr (@attrs) {
        _attach6($attr, $REG{x});
    }

    is(ref($attrs[0]{associated_class}), 'M',
        'Class::MOP attach_to_class pattern: iter 0 stays defined');
    is(ref($attrs[1]{associated_class}), 'M',
        'Class::MOP attach_to_class pattern: iter 1 stays defined');
    is(ref($attrs[2]{associated_class}), 'M',
        'Class::MOP attach_to_class pattern: iter 2 stays defined');
}

done_testing();
