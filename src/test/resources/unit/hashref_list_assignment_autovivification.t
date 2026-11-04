#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;

my ($self, $type);
($self, %$type) = (
    'Object::Hybrid',
    reftype => 'HASH',
    tieable => 0,
    mutable => 0,
    caller  => 0,
);

is($self, 'Object::Hybrid', 'scalar target receives its list assignment value');
is(ref($type), 'HASH', 'list assignment autovivifies the hash reference');
is_deeply(
    $type,
    { reftype => 'HASH', tieable => 0, mutable => 0, caller => 0 },
    'hash target receives all key/value pairs',
);

my $initialized = {};
my $initialized_self;
($initialized_self, %$initialized) = ('Initialized', key => 'value');
is($initialized_self, 'Initialized', 'initialized reference scalar target is assigned');
is($initialized->{key}, 'value', 'initialized hash reference still receives list assignment');

done_testing();
