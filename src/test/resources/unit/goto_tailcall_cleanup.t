use strict;
use warnings;
use Test::More;

{
    package GotoCleanupTarget;
    sub target { }
    sub DESTROY { undef &target }
    eval { sub { my $guard = bless []; goto &target }->() };
    ::like($@, qr/^Goto undefined subroutine &GotoCleanupTarget::target at /,
        'goto reports a target undefed during source cleanup');
}

{
    package GotoCleanupDestroy;
    our @destroyed;
    sub DESTROY { push @destroyed, $_[0][0] }
    sub target { }
    sub trampoline {
        push @_, 'sentinel';
        goto &target;
    }
    trampoline(bless [$_]) for 1 .. 3;
    ::is_deeply(\@destroyed, [1, 2, 3],
        'goto releases temporary incoming arguments at each tail call');
}

{
    package GotoCleanupStatementBoundary;
    our ($iteration, @destroyed);
    sub DESTROY { push @destroyed, $_[0][0] }
    sub target {
        ::is(scalar @destroyed, $iteration - 1,
            'goto destroys the prior call temporary before the next target');
    }
    sub trampoline {
        push @_, 'sentinel', {};
        goto &target;
    }
    for $iteration (1 .. 3) {
        trampoline(bless([$iteration], 'GotoCleanupStatementBoundary'), 'argument');
    }
    ::is_deeply(\@destroyed, [1, 2, 3],
        'goto destroys each argument temporary at its completed handoff');
}

{
    package GotoCleanupEval;
    sub target { }
    eval 'goto &target';
    ::like($@, qr/^Can't goto subroutine from an eval-string/,
        'goto reports the eval-string restriction');
}

{
    package GotoCleanupDynamicEval;
    sub TIESCALAR { bless [pop] }
    sub FETCH { $_[0][0] }
    tie my $target, 'GotoCleanupDynamicEval', sub { 'dynamic tail target' };
    ::is(eval { sub { goto $target }->() }, 'dynamic tail target',
        'dynamic goto in a normal sub remains valid when called from eval');
}

package main;

{
    use utf8;
    eval { goto &因 };
    ::like($@, qr/Goto undefined subroutine &main::因/,
        'eval block catches an undefined Unicode goto target');
}

{
    package GotoCleanupAutoload;
    our $called;
    our $AUTOLOAD;
    sub trampoline { goto &missing }
    sub AUTOLOAD { $called = $AUTOLOAD }
    trampoline('argument');
    ::is($called, 'GotoCleanupAutoload::missing',
        'goto resolves a named target through AUTOLOAD after source cleanup');
}

{
    no warnings 'uninitialized';
    my $source = sub { goto &utf8::encode };
    local @_ = ();
    $#_++;
    &$source;
    ::is($_[0], '', 'goto to utf8::encode reifies a sparse argument slot');
}

{
    package GotoCleanupSlots;
    our $absent_after_undef;
    my $source = sub { goto sub { $absent_after_undef = !defined *_{ARRAY} } };
    undef *_;
    eval { &$source };
    ::ok($absent_after_undef,
        'goto preserves an absent ARRAY slot after undef glob');
}

{
    sub {
        local *_;
        goto sub { ::is(*_{ARRAY}, undef,
            'goto preserves an absent ARRAY slot after local glob') };
    }->();
}

package main;

done_testing;
