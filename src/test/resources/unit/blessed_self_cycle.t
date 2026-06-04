#!/usr/bin/env perl
use strict;
use warnings;

use Test::More;
use Scalar::Util qw(weaken);

{
    package BlessedSelfCycle;

    sub new {
        bless { callbacks => [] }, shift;
    }

    sub on_ready {
        my $self = shift;
        push @{ $self->{callbacks} }, $_[0];
        return $self;
    }
}

my $weak;
my $weak_child;
my $weak_breakable;

sub install_void_self_cycle {
    my $future = BlessedSelfCycle->new;
    $weak = $future;
    weaken($weak);

    # The method returns $self, but the caller uses void context. The callback
    # still captures $future, so Perl's refcounting keeps this strong cycle
    # alive until the callback breaks it.
    $future->on_ready(sub { undef $future });
}

sub install_void_self_cycle_with_child {
    my $future = BlessedSelfCycle->new;
    $future->{child} = bless {}, 'BlessedSelfCycleChild';

    $weak = $future;
    weaken($weak);
    $weak_child = $future->{child};
    weaken($weak_child);

    $future->on_ready(sub { undef $future });
}

sub install_breakable_blessed_data_cycle {
    my $row = bless {}, 'BreakableBlessedDataCycle';
    my $source = { row => $row };
    $row->{source} = $source;

    $weak_breakable = $row;
    weaken($weak_breakable);
}

install_void_self_cycle();

ok defined($weak),
    'implicit void method return does not break a blessed callback self-cycle';

install_void_self_cycle_with_child();

sleep 6;

ok defined($weak_child),
    'quiet weak sweep keeps objects strongly reachable from a retained cycle';

install_breakable_blessed_data_cycle();

ok defined($weak_breakable),
    'weak ref sees a temporarily retained blessed data cycle';

$weak_breakable->{source} = undef;

ok !defined($weak_breakable),
    'breaking a blessed data cycle clears the weak ref';

done_testing;
