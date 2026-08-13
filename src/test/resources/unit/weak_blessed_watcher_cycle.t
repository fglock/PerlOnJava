use strict;
use warnings;
use Scalar::Util qw(weaken);
use Test::More;

our @registry;
our $destroyed = 0;

{
    package Local::Watcher;
    sub DESTROY { $main::destroyed++ }
}

sub install_watcher {
    my %state;
    my $watcher = bless [ sub { return $state{watcher} } ], 'Local::Watcher';
    push @registry, $watcher;
    weaken $registry[-1];

    $state{watcher} = $watcher;
}

install_watcher();

ok(defined $registry[0], 'weak registry entry survives through a captured hash cycle');
is($destroyed, 0, 'captured hash retains a blessed watcher with DESTROY');

done_testing;
