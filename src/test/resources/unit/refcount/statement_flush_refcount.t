#!/usr/bin/env perl
use strict;
use warnings;

use Test::More;
use Test2::Tools::Refcount qw(is_oneref);

sub consume_dynamic_reader {
    my ($object) = @_;

    my @queue;
    my $outer = sub {
        my ($self) = @_;
        my $length = 1;

        return sub {
            my ($self) = @_;
            return undef;
        };
    };

    my $ret = $outer->($object);
    $queue[0] = { on_read => $ret };
    undef $ret;

    my $on_read = $queue[0]{on_read};
    my $done = $on_read->($object);
    undef $on_read;

    shift @queue;
    return 1;
}

my $object = bless {}, 'StatementFlushRefcountTarget';

is_oneref($object, 'object starts with one owner');
consume_dynamic_reader($object);
is_oneref($object, 'discarded reader temporaries are flushed at statement boundary');

done_testing;
