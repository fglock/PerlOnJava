use strict;
use warnings;
use Test::More tests => 2;

our $destroyed = 0;

{
    package DiscardedCallbackCapturedObject;

    sub DESTROY { $main::destroyed++ }
}

sub discard_callback { return }

sub run_with_lazy_callback {
    my $object = bless {}, 'DiscardedCallbackCapturedObject';
    discard_callback(sub { $object->{unused} });
    is($destroyed, 0, 'object lives through callback argument expression');
}

run_with_lazy_callback();
is($destroyed, 1, 'discarded callback releases captured lexical');
