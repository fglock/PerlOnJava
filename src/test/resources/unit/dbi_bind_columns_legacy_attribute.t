use strict;
use warnings;
use Test::More tests => 8;
use DBI;

my $dbh = DBI->connect('dbi:SQLite:dbname=:memory:', '', '', {
    RaiseError => 1,
    PrintError => 0,
});

$dbh->do('CREATE TABLE tokenizer_map (funcid INTEGER PRIMARY KEY AUTOINCREMENT, funcname TEXT)');
$dbh->do('INSERT INTO tokenizer_map (funcname) VALUES (?)', undef,
    'Search::Fulltext::Worker');
my $generated_funcid = $dbh->func('last_insert_rowid');
ok(defined($generated_funcid) && $generated_funcid > 0,
    'SQLite reports the generated funcid after inserting the tokenizer map row');

my ($funcid, $funcname);
my $sth = $dbh->prepare('SELECT funcid, funcname FROM tokenizer_map');
$sth->execute;
ok($sth->bind_columns(undef, \$funcid, \$funcname),
    'bind_columns accepts the legacy leading undef attribute');
ok($sth->fetch, 'fetch retrieves a row with bound columns');
is($funcid, $generated_funcid, 'the generated funcid binds to the first selected column');
is($funcname, 'Search::Fulltext::Worker', 'the second selected column binds to the second scalar');

my ($hashref_funcid, $hashref_funcname);
$sth = $dbh->prepare('SELECT funcid, funcname FROM tokenizer_map');
$sth->execute;
ok($sth->bind_columns({}, \$hashref_funcid, \$hashref_funcname),
    'bind_columns accepts the legacy leading attribute hash');
ok($sth->fetch && $hashref_funcid == $generated_funcid && $hashref_funcname eq 'Search::Fulltext::Worker',
    'legacy attribute hash does not shift the bound columns');

$dbh->do('CREATE TABLE job (funcid INTEGER NOT NULL)');
$dbh->do('INSERT INTO job (funcid) VALUES (?)', undef, $funcid);
is($dbh->selectrow_array('SELECT funcid FROM job'), $generated_funcid,
    'bound funcid is preserved when stored on a job row');
