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

done_testing;
