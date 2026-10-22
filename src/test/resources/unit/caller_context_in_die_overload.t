#!/usr/bin/env perl

use strict;
use warnings;
use Test::More tests => 2;
use lib 'src/test/resources/unit/lib';
use Call::Context;

sub foo {
    Call::Context::must_be_list();
    return 1;
}

sub try_it {
    return foo();
}

eval { try_it() };
like($@, qr/non-list \(void\) context/, 'caller context in overloaded exception preserves void');

scalar eval { try_it() };
like($@, qr/non-list \(scalar\) context/, 'caller context in overloaded exception preserves scalar eval');
