#!/usr/bin/env perl

use strict;
use warnings;
use Test::More tests => 5;

our ($save_ok, $save_fileno, $copy_ok, $copy_fileno, $copy_has_io);

{
    package OpenDupPackage;

    local *STDOUT_SAVE;
    local *STDOUT_COPY;

    $main::save_ok = open(STDOUT_SAVE, ">&STDOUT");
    $main::save_fileno = fileno(STDOUT_SAVE);
    $main::copy_ok = open(STDOUT_COPY, ">&STDOUT_SAVE");
    $main::copy_fileno = fileno(STDOUT_COPY);
    $main::copy_has_io = *STDOUT_COPY{IO} ? 1 : 0;

    close STDOUT_COPY;
    close STDOUT_SAVE;
}

package main;

ok($save_ok, 'save a standard handle in the current package');
ok(defined $save_fileno, 'the package-local saved handle has a descriptor');
ok($copy_ok, 'two-argument open resolves its source in the current package');
ok(defined $copy_fileno, 'the duplicate of the package-local handle has a descriptor');
is($copy_has_io, 1, 'the destination IO slot is installed in the current package');
