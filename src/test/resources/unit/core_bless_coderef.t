use strict;
use warnings;
use Test::More tests => 3;

my $bless = \&CORE::bless;
my $object = $bless->([], 'UnitCoreBless');
is(ref($object), 'UnitCoreBless', 'CORE::bless can be called through a code reference');

sub bless_in_main {
    return &CORE::bless([]);
}
is(ref(bless_in_main()), 'main', 'one-argument CORE::bless uses the caller package');

my @objects = &CORE::bless([], 'UnitCoreBlessInList');
is(ref($objects[0]), 'UnitCoreBlessInList', 'CORE::bless returns one value in list context');
