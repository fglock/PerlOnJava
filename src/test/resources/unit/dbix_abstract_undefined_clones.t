use strict;
use warnings;
use DBI;
use Test::More tests => 3;

{
    package Regression::DBIxAbstractWrapper;
    sub new { bless {dbh => $_[1]}, $_[0] }
    sub DESTROY { delete $_[0]->{dbh} }
}

my $connection = DBI->connect('dbi:SQLite:dbname=:memory:', '', '');
my $dbh = Regression::DBIxAbstractWrapper->new($connection);
ok($dbh->{dbh}->disconnect, 'the first wrapped DBI handle disconnects');
$dbh->{dbh} = DBI->connect('dbi:SQLite:dbname=:memory:', '', '');

my $other_wrapper = Regression::DBIxAbstractWrapper->new($dbh->{dbh});
$other_wrapper->DESTROY;
ok($dbh->{dbh}->ping, 'the original wrapped handle remains connected');
is(@{$dbh->{CLONES}}, 0,
    'missing CLONES dereferences as an empty list after reconnect and wrapper destruction');
