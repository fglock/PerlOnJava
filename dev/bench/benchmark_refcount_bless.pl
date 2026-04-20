#!/usr/bin/env perl
# benchmark_refcount_bless.pl
# Stresses the bless / DESTROY / MortalList path.
# Sensitive to changes in ReferenceOperators.bless, RuntimeScalar.setLargeRefCounted,
# and MortalList.flush throughput.

use strict;
use warnings;
use Benchmark qw(timethese);

{ package WithDestroy;
  sub new { bless { id => $_[1] }, $_[0] }
  sub DESTROY {}
}

{ package NoDestroy;
  sub new { bless { id => $_[1] }, $_[0] }
}

my $N = $ENV{BENCH_N} || 100_000;

sub bless_and_drop_destroy {
    # Blessed temp, never stored — exercises mortal flush immediately.
    WithDestroy->new(1);
    return 1;
}

sub bless_and_drop_nodestroy {
    NoDestroy->new(1);
    return 1;
}

sub bless_store_drop {
    my $o = WithDestroy->new(1);
    return 1;
}

sub bless_hash_store {
    my $o = WithDestroy->new(1);
    my %h;
    $h{obj} = $o;
    return 1;
}

sub bless_pass_through_subs {
    # Passes a blessed temp through two my-list assignments.
    # This is the setFromList fast-path we modified.
    my $outer = sub { my ($a) = @_; $a };
    my $inner = sub { WithDestroy->new(99) };
    my $r = $outer->($inner->());
    return 1;
}

timethese($N, {
    'bless_drop_destroy'       => \&bless_and_drop_destroy,
    'bless_drop_nodestroy'     => \&bless_and_drop_nodestroy,
    'bless_store_drop'         => \&bless_store_drop,
    'bless_hash_store'         => \&bless_hash_store,
    'bless_pass_through_subs'  => \&bless_pass_through_subs,
});
