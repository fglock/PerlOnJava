use strict;
use warnings;
use Test::More;

BEGIN {
    eval { require DBI; require DBD::SQLite; require DBD::SQLite::Constants; 1 }
        or plan skip_all => 'DBI and DBD::SQLite required';
}

ok(defined $DBD::SQLite::sqlite_version,
    'DBD::SQLite exposes the embedded SQLite version');
like($DBD::SQLite::sqlite_version, qr/^\d+\.\d+\.\d+$/,
    'embedded SQLite version has the expected format');

my $dbh = DBI->connect('dbi:SQLite:dbname=:memory:', '', '', {
    RaiseError => 1,
});
my ($runtime_version) = $dbh->selectrow_array('select sqlite_version()');
is($DBD::SQLite::sqlite_version, $runtime_version,
    'reported SQLite version matches the running engine');
is(DBD::SQLite::Constants::SQLITE_OPEN_READONLY(), 1,
    'read-only open flag matches DBD::SQLite');
is(DBD::SQLite::Constants::DBD_SQLITE_STRING_MODE_UNICODE_FALLBACK(), 5,
    'Unicode fallback mode matches DBD::SQLite');

done_testing;
