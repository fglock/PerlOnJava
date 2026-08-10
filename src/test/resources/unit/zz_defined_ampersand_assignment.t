#!/usr/bin/env perl

use strict;
use warnings;
use Test::More tests => 4;

{
    package DefinedAmpersandAssignment;

    sub value { $_[0] }

    sub value_is_defined {
        defined(my $result = &value) ? 1 : 0;
    }
}

ok(defined(&DefinedAmpersandAssignment::value),
    'direct defined(&sub) probes the CODE slot');
ok(!DefinedAmpersandAssignment::value_is_defined(undef),
    'ampersand call in a defined assignment can return undef');
ok(DefinedAmpersandAssignment::value_is_defined('value'),
    'ampersand call in a defined assignment can return a value');
is(DefinedAmpersandAssignment::value_is_defined(0), 1,
    'defined tests the call result rather than its truth');
