#!/usr/bin/env perl
use strict;
use warnings;

use Scalar::Util qw(weaken);
use Test::More tests => 5;

my ($dead_weak, $live_weak, $global_owner);
my @events;

my $first_reader = sub {
    my $released = [];
    $dead_weak = $released;
    weaken($dead_weak);

    my $shared = [];
    $live_weak = $shared;
    weaken($live_weak);
    $global_owner = $shared;

    push @events, 'first';
};

my $second_reader = sub {
    push @events, 'second';
    ok(!defined($dead_weak), 'scope-exited weak referent is cleared before the next callback');
    ok(defined($live_weak), 'a separate live strong owner keeps its weak observer defined');
};

my $nested_weak;
sub leave_nested_container_scope {
    my $child = [];
    $nested_weak = $child;
    weaken($nested_weak);
    my $container = [$child];
}

$first_reader->();
$second_reader->();

is_deeply(\@events, ['first', 'second'], 'reader callbacks run in order');

leave_nested_container_scope();
ok(!defined($nested_weak), 'weak refs to children of an exited aggregate are cleared');

undef $global_owner;
ok(!defined($live_weak), 'weak observer clears when the remaining strong owner is released');
