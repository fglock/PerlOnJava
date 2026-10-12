use strict;
use warnings;
use utf8;
use Test::More;
use DBI;

my $dbh = DBI->connect('dbi:SQLite:dbname=:memory:', '', '', { sqlite_unicode => 1 });
plan skip_all => 'SQLite DBI driver is unavailable' unless $dbh;

my $sth = $dbh->prepare('SELECT ? AS locale_text');
$sth->execute("午前 ٠");
my $array_row = $sth->fetchrow_arrayref;
is($array_row->[0], "午前 ٠", 'sqlite_unicode returns CLDR locale text as characters');
ok(utf8::is_utf8($array_row->[0]), 'sqlite_unicode text retains its Unicode flag');

$sth->execute("午前 ٠");
my $hash_row = $sth->fetchrow_hashref;
is($hash_row->{locale_text}, "午前 ٠", 'hash fetch preserves locale code points');
ok(utf8::is_utf8($hash_row->{locale_text}), 'hash fetch retains the Unicode flag');

$sth->finish;
$dbh->disconnect;
done_testing;
