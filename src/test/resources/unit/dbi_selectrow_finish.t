#!/usr/bin/perl
use strict;
use warnings;
use Test::More;
use DBI;

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

$dbh->do('CREATE TABLE items (id INTEGER PRIMARY KEY, name TEXT)');
$dbh->do(q{INSERT INTO items (id, name) VALUES (1, 'one')});

my $array_sth = $dbh->prepare('SELECT name FROM items WHERE id = 1');
my $array_row = $dbh->selectrow_arrayref($array_sth);
is_deeply($array_row, ['one'], 'selectrow_arrayref returns the row');
ok(!$array_sth->FETCH('Active'), 'selectrow_arrayref finishes a successful statement');

my $hash_sth = $dbh->prepare('SELECT name FROM items WHERE id = 1');
my $hash_row = $dbh->selectrow_hashref($hash_sth);
is($hash_row->{name}, 'one', 'selectrow_hashref returns the row');
ok(!$hash_sth->FETCH('Active'), 'selectrow_hashref finishes a successful statement');

done_testing;
