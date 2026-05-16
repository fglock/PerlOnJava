use strict;
use warnings;
use Test::More;
use DBI;

# Regression guard for JDBC DBI.pm-style fetchrow_hashref($sth, 'NAME_lc')
# (DBIx::Simple lc_columns defaults; upstream DBIx-Simple t/sqlite.t hashes).

BEGIN {
    eval { require DBD::SQLite; 1 }
        or plan skip_all => 'DBD::SQLite required';
}

my $dbh = DBI->connect(
    'dbi:SQLite:dbname=:memory:',
    '',
    '',
    { RaiseError => 1, PrintError => 0 },
);

$dbh->do('CREATE TABLE xyzzy (FOO, bar)');
$dbh->do(q{INSERT INTO xyzzy VALUES ('a', 'b')});

my $sth = $dbh->prepare('SELECT FOO, bar FROM xyzzy ORDER BY foo');
$sth->execute;

my $href = $sth->fetchrow_hashref('NAME_lc');
is_deeply(
    $href,
    { foo => 'a', bar => 'b' },
    'fetchrow_hashref honors NAME_lc (lowercase mixed-case labels)',
);

done_testing;
