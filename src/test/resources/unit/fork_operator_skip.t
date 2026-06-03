#!/usr/bin/env perl
use strict;
use warnings;

use Test::More;

plan tests => 3;

pass 'emitted a test before fork';

my $child = fork();
SKIP: {
    skip "fork unavailable: $!", 2 unless defined $child;

    if ($child == 0) {
        exit 0;
    }

    ok $child > 0, 'operator-form fork returns a child pid in the parent';
    waitpid($child, 0);
    is $?, 0, 'child process exits cleanly';
}
