use strict;
use warnings;
use Scalar::Util qw(weaken isweak);
use Test::More tests => 5;

package Issue1649::ReentrantWeakOwner;

sub new { bless { name => $_[1] }, $_[0] }

sub DESTROY {
    my ($self) = @_;
    push @main::destroyed, $self->{name};
    if ($self->{name} eq 'old') {
        $main::keeper = $main::slot;
        Scalar::Util::weaken($main::slot);
    }
}

package main;

our (@destroyed, $slot, $keeper);
$slot = Issue1649::ReentrantWeakOwner->new('old');
$slot = Issue1649::ReentrantWeakOwner->new('replacement');

ok(isweak($slot), 'the old destructor leaves the replacement slot weak');
is($slot->{name}, 'replacement', 'the weakened slot still observes the replacement');
is_deeply(\@destroyed, ['old'], 'the keeper retains the replacement through overwrite');

undef $keeper;
ok(!defined($slot), 'releasing the keeper clears the weak slot');
is_deeply(\@destroyed, [qw(old replacement)],
    'the replacement is destroyed when the last strong owner leaves');
