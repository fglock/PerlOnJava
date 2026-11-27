use strict;
use warnings;
use Test::More tests => 2;

BEGIN {
    package DefinedCodeSlotCompileTimeDelete;
    sub target { 'old' }
}

package main;

my $probe = sub { defined(&DefinedCodeSlotCompileTimeDelete::target) };

BEGIN {
    delete $DefinedCodeSlotCompileTimeDelete::{target};
}

ok($probe->(), 'compiled defined probe retains its CV after compile-time stash deletion');
ok(!exists($DefinedCodeSlotCompileTimeDelete::{target}),
    'the stash entry was deleted before the probe ran');
