use strict;
use warnings;

use DBI;
use Test::More tests => 10;

ok(*DBI::db::ping{CODE}, 'DBI::db::ping has a direct CODE slot');
ok(*DBI::db::commit{CODE}, 'DBI::db::commit has a direct CODE slot');
ok(*DBI::st::execute{CODE}, 'DBI::st::execute has a direct CODE slot');
ok(*DBI::st::finish{CODE}, 'DBI::st::finish has a direct CODE slot');

my $dbh = DBI->connect('dbi:ExampleP:dummy', '', '');
is(ref($dbh), 'DBI::db', 'driver connection returns a native-style outer db handle');
is($dbh->{ImplementorClass}, 'DBD::ExampleP::db', 'outer handle records its driver implementor');
ok($dbh->{PrintError}, 'DBI handles default PrintError to true');

is_deeply(
    [DBI->parse_dsn('dbi:ExampleP(foo=bar):dummy')],
    ['dbi', 'ExampleP', 'foo=bar', {foo => 'bar'}, 'dummy'],
    'parse_dsn returns native DBI components and attributes',
);

is_deeply([DBI->parse_dsn('not-a-dsn')], [], 'parse_dsn rejects non-DBI strings');

local $ENV{DBI_DSN};
local $ENV{DBI_DRIVER};
eval { DBI->connect() };
like($@, qr/can't work out what driver to use/i, 'connect without a DSN reports the native error');
