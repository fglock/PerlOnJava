use strict;
use warnings;

use Symbol qw(delete_package);
use Test::More tests => 3;

{
    package UnitDeletePackage;
    sub marker { 'present' }
}

ok(UnitDeletePackage->can('marker'), 'package method starts installed');
delete_package('UnitDeletePackage');
ok(!UnitDeletePackage->can('marker'), 'delete_package removes package methods');

{
    no strict 'refs';
    ok(!exists $main::{'UnitDeletePackage::'}, 'delete_package removes the package stash');
}
