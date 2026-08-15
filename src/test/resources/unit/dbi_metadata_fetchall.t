#!/usr/bin/env perl

use strict;
use warnings;
use Test::More tests => 5;
use File::Temp qw(tempfile);
use DBI;
use DBD::SQLite;

my ($fh, $db_file) = tempfile(SUFFIX => '.sqlite');
close $fh;
unlink $db_file;

my $dbh = DBI->connect(
    "dbi:SQLite:dbname=$db_file", '', '',
    { RaiseError => 1, AutoCommit => 1 },
);
is($dbh->{FetchHashKeyName}, 'NAME', 'DBI sets the default hash key name');
$dbh->do('create table alpha (id int)');
$dbh->do('create table beta (id int)');

my $sth = $dbh->table_info(undef, undef, '%', 'TABLE');
ok($sth->{Active}, 'table_info returns an active statement handle');

my $rows = $sth->fetchall_arrayref({});
is(ref($rows), 'ARRAY', 'metadata fetchall_arrayref returns an array reference');
is(scalar(@$rows), 2, 'metadata fetchall_arrayref returns both tables');
is_deeply(
    [ sort map { $_->{TABLE_NAME} } @$rows ],
    [qw(alpha beta)],
    'metadata hash rows expose TABLE_NAME',
);

END { unlink $db_file if defined $db_file }
