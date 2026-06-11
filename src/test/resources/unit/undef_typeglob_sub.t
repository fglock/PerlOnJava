#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;

{
    package UndefTypeglobSubTest;
    sub something { 42 }
}

is(UndefTypeglobSubTest::something(), 42, 'sub exists before typeglob undef');
undef *UndefTypeglobSubTest::something;
ok(!defined(&UndefTypeglobSubTest::something), 'typeglob undef clears CODE slot');

my $error = do {
    local $@;
    eval { UndefTypeglobSubTest::something() };
    $@;
};
like(
    $error,
    qr/Undefined subroutine \&UndefTypeglobSubTest::something called/,
    'calling a sub after undef *glob dies'
);

done_testing;
