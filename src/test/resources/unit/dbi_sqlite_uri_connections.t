#!/usr/bin/env perl

use strict;
use warnings;
use Test::More;
use File::Temp qw(tempfile);
use DBI;

eval { require DBD::SQLite; 1 }
    or plan skip_all => 'DBD::SQLite required';

plan tests => 4;

my ($file_a, $path_a) = tempfile(SUFFIX => '.sqlite');
close $file_a;
unlink $path_a;
my ($file_b, $path_b) = tempfile(SUFFIX => '.sqlite');
close $file_b;
unlink $path_b;

my $dsn_a = "dbi:SQLite:uri=file:$path_a?mode=rwc";
my $dsn_b = "dbi:SQLite:uri=file:$path_b?mode=rwc";

my $dbh = DBI->connect($dsn_a, '', '', {
    RaiseError => 1,
    PrintError => 0,
});
$dbh->do('create table uri_schema (value integer)');
$dbh->do('insert into uri_schema values (1185)');
$dbh->disconnect;

$dbh = DBI->connect($dsn_a, '', '', {
    RaiseError => 1,
    PrintError => 0,
});
my ($value) = $dbh->selectrow_array('select value from uri_schema');
is($value, 1185, 'URI file database keeps its schema across connections');
$dbh->disconnect;

my $other = DBI->connect($dsn_b, '', '', {
    RaiseError => 1,
    PrintError => 0,
});
my $has_schema = eval {
    $other->selectrow_array('select value from uri_schema');
    1;
};
ok(!$has_schema, 'a distinct URI file DSN has an isolated schema');
$other->do('create table uri_schema (value integer)');
$other->do('insert into uri_schema values (2206)');
my ($other_value) = $other->selectrow_array('select value from uri_schema');
is($other_value, 2206, 'distinct URI file database remains usable');
$other->disconnect;

ok(unlink($path_a) && unlink($path_b), 'temporary URI databases are removed');
