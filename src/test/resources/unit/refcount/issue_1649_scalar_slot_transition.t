use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);

package Issue1649::ScalarSlotTransition;

sub DESTROY {
    my ($self) = @_;
    my $current = $main::slot;
    push @main::DESTROYED,
        [$self->{id}, defined($current) ? $current->{id} : 'undef'];
}

package main;

our ($slot, @DESTROYED);
@DESTROYED = ();

my $old = bless { id => 'old' }, 'Issue1649::ScalarSlotTransition';
$slot = $old;
undef $old;

my $weak_old = $slot;
weaken($weak_old);
my $copy = $slot;
my $new = bless { id => 'new' }, 'Issue1649::ScalarSlotTransition';
my $weak_new = $new;
weaken($weak_new);
my $slot_alias = \$slot;

$slot = $new;
undef $new;
is(scalar @DESTROYED, 0,
    'replacing a slot preserves the old referent while a copied reference owns it');

undef $copy;
is_deeply(\@DESTROYED, [['old', 'new']],
    'the displaced referent is destroyed after the slot contains its replacement');
ok(!defined($weak_old), 'the old weak observer clears with the final strong release');

$$slot_alias = undef;
is_deeply(\@DESTROYED, [['old', 'new'], ['new', 'undef']],
    'undef through an alias publishes undef before destroying the old referent');
ok(!defined($weak_new), 'the new weak observer clears after undef releases its owner');

done_testing();
