use strict;
use warnings;
use DBI;
use Test::More tests => 3;

is_deeply(
    [DBI->parse_dsn('dbi:mysql:database=mytbl')],
    ['dbi', 'mysql', undef, undef, 'database=mytbl'],
    'parse_dsn accepts the required dbi prefix',
);

is_deeply([DBI->parse_dsn('bi:mysql:database=mytbl')], [],
    'parse_dsn rejects a missing leading d');
is_deeply([DBI->parse_dsn('xbi:mysql:database=mytbl')], [],
    'parse_dsn rejects a non-DBI scheme');
