#!/usr/bin/env perl
use strict;
use warnings;

use Test::More;
use Test2::Tools::Refcount qw(is_refcount);
use Scalar::Util qw(weaken);

{
    package GotoWeakselfTailcallTarget;
    sub DESTROY {}
}

sub replace_weakself {
    my $self = shift;
    my ($code) = @_;

    weaken $self;

    return sub {
        shift @_;
        unshift @_, $self;
        goto &$code;
    };
}

my $object = bless {}, 'GotoWeakselfTailcallTarget';
my $second_owner = $object;
my $take_return_path = 1;

my $callback = replace_weakself($object, sub {
    if ($take_return_path) {
        my $temporary = 1;
        return undef;
    }
    my $captured_outer_object = $object;
    return undef;
});

is_refcount($object, 2, 'object starts with lexical and secondary owner');
$callback->('delegated-self');
is_refcount($object, 2, 'goto weakself trampoline preserves captured caller owners after explicit return');

done_testing;
