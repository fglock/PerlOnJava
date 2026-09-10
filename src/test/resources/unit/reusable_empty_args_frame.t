use strict;
use warnings;
use Test::More;

my $calls = 0;
my $leaf = sub { ++$calls };
my $outer = sub { $leaf->(); $leaf->(); return $calls };

is($outer->(), 2, 'nested argument-independent closures may share empty frames');
is($outer->(), 4, 'reused empty frame remains valid after nested calls return');

my $observes_args = sub {
    push @_, 'local mutation';
    return scalar @_;
};

is($observes_args->(), 1, 'an @_ observer receives a fresh empty frame');
is($observes_args->(), 1, 'argument-frame mutation cannot leak into next call');

done_testing;
