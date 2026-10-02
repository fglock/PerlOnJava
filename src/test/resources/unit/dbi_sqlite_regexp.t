use strict;
use warnings;
use Test::More tests => 3;
use DBI;

my $dbh = DBI->connect('dbi:SQLite:dbname=:memory:', '', '', {
    RaiseError => 1,
    PrintError => 0,
});
$dbh->do('CREATE TABLE regexp_values (value TEXT)');
$dbh->do('INSERT INTO regexp_values VALUES (?)', undef, 'alpha');
$dbh->do('INSERT INTO regexp_values VALUES (?)', undef, 'beta');

my ($matched) = $dbh->selectrow_array(
    'SELECT value FROM regexp_values WHERE value REGEXP ?',
    {},
    '^a.*a$',
);
is($matched, 'alpha', 'SQLite REGEXP uses Perl-compatible matching');

my ($no_match) = $dbh->selectrow_array(
    'SELECT count(*) FROM regexp_values WHERE value REGEXP ?',
    {},
    '^z',
);
is($no_match, 0, 'SQLite REGEXP returns false when no row matches');

my ($perl_escape_match) = $dbh->selectrow_array(
    q{SELECT 'a42z' REGEXP ?},
    {},
    q{^a\d+z$},
);
ok($perl_escape_match, 'SQLite REGEXP supports Perl regular-expression escapes');
