#!/usr/bin/perl
use strict;
use warnings;
use Test::More;
use DBI;

BEGIN {
    eval { require DBD::SQLite; 1 }
        or plan skip_all => 'DBD::SQLite required';
}

{
    package DBICTest::Cursor;

    our $DESTROYED = 0;

    sub new {
        my ($class, $sth) = @_;
        return bless { sth => $sth }, $class;
    }

    sub DESTROY {
        my $self = shift;
        $DESTROYED++;
        eval { $self->{sth}->finish if $self->{sth} };
    }
}

my $dbh = DBI->connect(
    'dbi:SQLite:dbname=:memory:',
    '',
    '',
    { RaiseError => 1, PrintError => 0 },
);

$dbh->do('CREATE TABLE items (id INTEGER PRIMARY KEY)');
$dbh->do('INSERT INTO items (id) VALUES (1)');

{
    my $sth = $dbh->prepare('SELECT id FROM items');
    $sth->execute;
    is_deeply($sth->fetchrow_arrayref, [1], 'cursor fetched first row');
    my $cursor = DBICTest::Cursor->new($sth);
}

ok(
    eval { $dbh->do('DROP TABLE items'); 1 },
    'DDL succeeds after unreachable cursor cleanup',
) or diag $@;

ok($DBICTest::Cursor::DESTROYED >= 1, 'unreachable cursor was destroyed');

done_testing;
