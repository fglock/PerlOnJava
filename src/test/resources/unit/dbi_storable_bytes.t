use strict;
use warnings;
use Test::More;
use DBI;
use Storable qw(nfreeze thaw);

my $dbh = DBI->connect(
    'dbi:SQLite:dbname=:memory:',
    '',
    '',
    { RaiseError => 1, PrintError => 0 },
);

$dbh->do('CREATE TABLE t (v TEXT)');

my $payload = { a => 1, b => [ { c => 2 } ], d => 3 };
my $frozen = nfreeze($payload);

like($frozen, qr/[\x80-\xff]/, 'frozen payload exercises binary bytes');

$dbh->do('INSERT INTO t (v) VALUES (?)', undef, $frozen);
my ($roundtrip) = $dbh->selectrow_array('SELECT v FROM t');

is(length($roundtrip), length($frozen), 'DBI preserves binary Storable length');
is(unpack('H*', $roundtrip), unpack('H*', $frozen), 'DBI preserves binary Storable bytes');
is_deeply(thaw($roundtrip), $payload, 'DBI round-tripped Storable payload thaws');

done_testing;
