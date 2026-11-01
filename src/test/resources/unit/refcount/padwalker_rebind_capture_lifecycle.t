use strict;
use warnings;
use Test::More tests => 5;
use PadWalker qw(set_closed_over);

our @DESTROYED;

{
    package Ownership::PadRebindProbe;

    sub DESTROY {
        push @main::DESTROYED, $_[0]->{label};
    }
}

my $old = bless { label => 'old' }, 'Ownership::PadRebindProbe';
my $closure = sub { $old };
is($closure->()->{label}, 'old', 'closure begins with the original capture');

{
    my $replacement = bless { label => 'replacement' }, 'Ownership::PadRebindProbe';
    set_closed_over($closure, { '$old' => \$replacement });
    is($closure->()->{label}, 'replacement', 'closure uses the rebound capture');
}

undef $old;
is_deeply(\@DESTROYED, ['old'], 'the displaced capture is destroyed after its last owner');
is($closure->()->{label}, 'replacement', 'the closure keeps the replacement cell alive');
undef $closure;
is_deeply(\@DESTROYED, ['old', 'replacement'],
    'releasing the closure destroys the final replacement capture');
