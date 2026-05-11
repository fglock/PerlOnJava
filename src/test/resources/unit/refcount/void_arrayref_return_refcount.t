#!/usr/bin/env perl
use strict;
use warnings;

use Test::More;
use Test2::Tools::Refcount qw(is_oneref);

my $captured_size;

sub invoke_event_like {
    my ($self, $code, @args) = @_;
    return [ $code->($self, @args) ];
}

sub configure_like {
    my ($self) = @_;
    my $code = sub { (undef, $captured_size) = @_ };
    invoke_event_like($self, $code, 0);
    return;
}

my $object = bless {}, 'VoidArrayRefReturnRefcountTarget';

is_oneref($object, 'object starts with one owner');
configure_like($object);
is_oneref($object, 'discarded event return arrayref releases temporary self');

done_testing;
