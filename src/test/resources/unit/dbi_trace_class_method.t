use strict;
use warnings;
use Test::More tests => 3;
use DBI;

is(DBI->trace(0), 0, 'class-level trace can be disabled');
is(DBI->trace(2), 0, 'class-level trace can be enabled');

my $dbh = DBI->connect('dbi:ExampleP:dummy', '', '');
is($dbh->trace(0), 2, 'handle-level trace still works');
DBI->trace(0);
