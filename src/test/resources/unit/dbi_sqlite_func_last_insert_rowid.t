use strict;
use warnings;
use Test::More tests => 3;
use DBI;

my $dbh = DBI->connect('dbi:SQLite:dbname=:memory:', '', '', {
    RaiseError => 1,
    PrintError => 0,
});

$dbh->do('CREATE TABLE funcmap (funcid INTEGER PRIMARY KEY AUTOINCREMENT, funcname TEXT)');
$dbh->do('INSERT INTO funcmap (funcname) VALUES (?)', undef, 'Example::Worker');
my $funcid = $dbh->func('last_insert_rowid');
ok(defined($funcid) && $funcid =~ /^\d+$/ && $funcid > 0,
    'SQLite func dispatch returns the generated funcmap ID');

$dbh->do('CREATE TABLE job (funcid INTEGER NOT NULL)');
$dbh->do('INSERT INTO job (funcid) VALUES (?)', undef, $funcid);
is($dbh->selectrow_array('SELECT funcid FROM job'), $funcid,
    'generated funcid can be stored on a subsequent job row');

is($dbh->selectrow_array('SELECT COUNT(*) FROM job WHERE funcid = ?', undef, $funcid), 1,
    'job row is associated with its generated funcid');
