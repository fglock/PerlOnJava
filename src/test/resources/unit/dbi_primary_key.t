use strict;
use warnings;
use Test::More;

BEGIN {
    eval { require DBI; require DBD::SQLite; 1 }
        or plan skip_all => 'DBI and DBD::SQLite required';
}

my $dbh = DBI->connect(
    'dbi:SQLite:dbname=:memory:',
    '',
    '',
    { RaiseError => 1, PrintError => 0 },
);

$dbh->do('CREATE TABLE composite_key (payload TEXT, left_id INTEGER, right_id INTEGER, PRIMARY KEY (left_id, right_id))');

is_deeply(
    [ $dbh->primary_key(undef, undef, 'composite_key') ],
    [ qw(left_id right_id) ],
    'primary_key accepts undef metadata patterns and returns key columns in order',
);

my $sth = $dbh->primary_key_info(undef, undef, 'composite_key');
my @from_info;
while (my $row = $sth->fetchrow_hashref) {
    push @from_info, $row->{COLUMN_NAME};
}
is_deeply(\@from_info, [ qw(left_id right_id) ], 'primary_key_info accepts undef metadata patterns');

done_testing;
