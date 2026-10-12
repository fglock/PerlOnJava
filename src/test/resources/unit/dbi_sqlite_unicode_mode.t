use strict;
use warnings;
use utf8;
use Test::More;
use DBI;
use DBD::SQLite::Constants qw(DBD_SQLITE_STRING_MODE_UNICODE_FALLBACK);

my $dbh = DBI->connect('dbi:SQLite:dbname=:memory:', '', '');
plan skip_all => 'SQLite DBI driver is unavailable' unless $dbh;
$dbh->{sqlite_string_mode} = DBD_SQLITE_STRING_MODE_UNICODE_FALLBACK;

my $sth = $dbh->prepare('SELECT ? AS locale_text');
$sth->execute("午前 ٠");
my $array_row = $sth->fetchrow_arrayref;
is($array_row->[0], "午前 ٠", 'SQLite Unicode fallback mode returns CLDR text as characters');
ok(utf8::is_utf8($array_row->[0]), 'SQLite Unicode fallback mode retains the Unicode flag');

$sth->execute("午前 ٠");
my $hash_row = $sth->fetchrow_hashref;
is($hash_row->{locale_text}, "午前 ٠", 'hash fetch preserves CLDR locale code points');
ok(utf8::is_utf8($hash_row->{locale_text}), 'hash fetch retains the Unicode fallback flag');

$sth->finish;
$dbh->disconnect;
done_testing;
