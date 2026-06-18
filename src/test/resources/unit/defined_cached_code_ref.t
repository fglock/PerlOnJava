#!/usr/bin/env perl

use strict;
use warnings;
use Test::More tests => 3;

BEGIN {
    package DefinedCachedCodeRefExporter;

    sub import {
        my $caller = caller;
        no strict 'refs';
        *{"${caller}::cached_code"} = sub { 1 };
    }

    sub unimport {
        my $caller = caller;
        no strict 'refs';
        delete ${"${caller}::"}{cached_code};
    }
}

{
    package DefinedCachedCodeRefConsumer;

    BEGIN { DefinedCachedCodeRefExporter->import; }
    ::ok(defined(&cached_code), 'defined(&name) keeps compile-time imported CV');
    BEGIN { DefinedCachedCodeRefExporter->unimport; }
}

ok(!DefinedCachedCodeRefConsumer->can('cached_code'), 'unimport removed the visible stash entry');

{
    package DefinedCachedCodeRefStub;
    sub declared_only;
    ::ok(!defined(&declared_only), 'forward-declared sub is still not defined');
}
