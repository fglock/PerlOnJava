#!/usr/bin/env perl
use strict;
use warnings;

use Test::More;

{
    package Foo;
    sub bar { 1 }
    sub new { bless {}, shift }
}

SKIP: {
    skip "This Perl does not support fork()", 1
        if not Foo->bar;

    my $routine = Foo->new(
        model => "fork",
        setup => [
            env => { FOO => "Here is a random string" },
        ],
    );

    ok $routine, 'SKIP block with postfix if before model/setup args parses';
}

done_testing;
