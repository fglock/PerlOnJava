use strict;
use warnings;
use Test::More;

BEGIN {
    eval { require DBI; require DBD::SQLite; 1 }
        or plan skip_all => 'DBI and DBD::SQLite required';
}

plan tests => 3;

my $dbh = DBI->connect(
    'dbi:SQLite:dbname=:memory:',
    '',
    '',
    { RaiseError => 1, PrintError => 0 },
);

my $result = $dbh->do('-- comment only');
ok(defined $result, 'comment-only do returns a defined result');
ok($result, 'comment-only do returns DBI true zero');
is(0 + $result, 0, 'comment-only do reports zero affected rows');
