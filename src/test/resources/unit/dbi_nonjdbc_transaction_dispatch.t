use strict;
use warnings;
use Test::More tests => 5;
use DBI;
use DBD::ExampleP;

our @calls;
my $dbh = DBI->connect('dbi:ExampleP:dummy', '', '');
{
    no warnings qw(once redefine);
    local *DBD::ExampleP::db::begin_work = sub { push @calls, 'begin_work'; 1 };
    local *DBD::ExampleP::db::commit = sub { push @calls, 'commit'; 1 };
    local *DBD::ExampleP::db::rollback = sub { push @calls, 'rollback'; 1 };

ok($dbh->begin_work, 'begin_work delegates to a non-JDBC driver');
ok($dbh->commit, 'commit delegates to a non-JDBC driver');
ok($dbh->begin_work, 'a second transaction can begin');
ok($dbh->rollback, 'rollback delegates to a non-JDBC driver');
is_deeply(
    \@calls,
    [qw(begin_work commit begin_work rollback)],
    'driver transaction methods run in order',
);
}
