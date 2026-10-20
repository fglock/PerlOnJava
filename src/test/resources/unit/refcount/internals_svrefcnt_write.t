use strict;
use warnings;
use Test::More;

my $scalar = 42;
is(Internals::SvREFCNT($scalar, 7), 7,
    'setting a plain scalar refcount returns the requested count');
is(Internals::SvREFCNT($scalar), 7,
    'a plain scalar refcount write is visible to the next read');

my $array = [];
is(Internals::SvREFCNT($array, 7), 7,
    'setting an array referent refcount returns the requested count');
is(Internals::SvREFCNT($array), 7,
    'an array referent refcount write is visible to the next read');

my $hash = {};
is(Internals::SvREFCNT($hash, 7), 7,
    'setting a hash referent refcount returns the requested count');
is(Internals::SvREFCNT($hash), 7,
    'a hash referent refcount write is visible to the next read');

done_testing;
