use strict;
use warnings;
use Test::More;

our @unit_delete_snapshot;
sub unit_delete_snapshot {}

$unit_delete_snapshot[0] = 42;
my $glob = *unit_delete_snapshot;
delete $::{unit_delete_snapshot};

ok(defined *$glob{ARRAY}, 'saved glob keeps ARRAY slot after stash delete');
is((*$glob{ARRAY})->[0], 42, 'saved glob ARRAY slot keeps old values');

*unit_delete_snapshot = *$glob{ARRAY};
is($unit_delete_snapshot[0], 42, 'ARRAY slot can be restored from saved glob');

done_testing;
