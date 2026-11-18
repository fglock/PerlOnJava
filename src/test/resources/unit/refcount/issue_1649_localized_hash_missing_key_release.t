use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);

package Issue1649::LocalizedHashMissingKey;

sub DESTROY {
    my ($self) = @_;
    my $current = exists $main::values{temporary}
        ? $main::values{temporary}{id}
        : 'absent';
    push @main::DESTROYED, [$self->{id}, $current];
}

package main;

our (%values, @DESTROYED);
@DESTROYED = ();

my $weak;
{
    local $values{temporary};
    my $temporary = bless { id => 'temporary' },
        'Issue1649::LocalizedHashMissingKey';
    $weak = $temporary;
    weaken($weak);
    $values{temporary} = $temporary;
}

is_deeply(\@DESTROYED, [['temporary', 'absent']],
    'localized missing hash slot is removed before its value is destroyed');
ok(!defined($weak), 'the localized value weak observer clears on scope exit');
ok(!exists($values{temporary}), 'scope exit restores the originally absent key');

done_testing();
