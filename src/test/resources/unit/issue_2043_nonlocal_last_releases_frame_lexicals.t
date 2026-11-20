use strict;
use warnings;
use Test::More;

our (@guard_cleanups, $resumed_inside_sub);

{
    package Issue2043::ScopeGuard;

    sub new { bless [$_[1]], $_[0] }
    sub DESTROY { $_[0]->[0]->() }
}

sub leave_through_callback {
    my $guard = Issue2043::ScopeGuard->new(
        sub { push @guard_cleanups, 'released' },
    );
    $_[0]->();
    $resumed_inside_sub = 1;
}

my $resumed_after_block = 0;
OUTER: {
    leave_through_callback(sub { last OUTER });
    $resumed_after_block = 1;
}

is($resumed_inside_sub || 0, 0, 'last skips the rest of the active sub');
is($resumed_after_block, 0, 'last exits the labeled block');
is_deeply(\@guard_cleanups, ['released'], 'DESTROY runs for a local in the unwound sub');

done_testing();
