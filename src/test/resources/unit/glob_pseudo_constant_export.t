use strict;
use warnings;
no warnings 'once';

print "1..4\n";

no strict 'refs';
$::{source} = \"value";
*{'target'} = \&{'source'};
print eval('target') eq 'value'
    ? "ok 1 - proxy constant exports through a typeglob\n"
    : "not ok 1 - proxy constant exports through a typeglob\n";
print eval('source') eq 'value'
    ? "ok 2 - exporting leaves source constant intact\n"
    : "not ok 2 - exporting leaves source constant intact\n";

$::{self_export} = \"self";
*{'self_export'} = \&{'self_export'};
print eval('self_export()') eq 'self'
    ? "ok 3 - self-assigned proxy constant remains callable\n"
    : "not ok 3 - self-assigned proxy constant remains callable\n";
print ref($::{source}) eq 'SCALAR'
    ? "ok 4 - source stash representation remains a scalar reference\n"
    : "not ok 4 - source stash representation remains a scalar reference\n";
