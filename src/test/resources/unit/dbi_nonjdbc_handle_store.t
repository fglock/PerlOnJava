use strict;
use warnings;
use DBI;
use Test::More tests => 6;

our @store_calls;
our @bind_calls;
$INC{'DBD/RegressionStore.pm'} = __FILE__;

{
    package DBD::RegressionStore;
    our $drh;
    sub driver {
        return $drh if $drh;
        ($drh) = DBI::_new_drh('DBD::RegressionStore::dr', {Name => 'RegressionStore'});
        return $drh;
    }
}

{
    package DBD::RegressionStore::dr;
    our @ISA = ('DBD::_::dr');
    our $imp_data_size = 0;
    sub connect {
        my ($drh, $name) = @_;
        return DBI::_new_dbh($drh, {Name => $name});
    }
}

{
    package DBD::RegressionStore::db;
    our @ISA = ('DBD::_::db');
    our $imp_data_size = 0;
    sub STORE {
        my ($dbh, $key, $value) = @_;
        push @main::store_calls, $key;
        return $dbh->SUPER::STORE($key, $value) if $key =~ /^private_/;
        $dbh->{$key} = $value;
        return 1;
    }
}

{
    package DBD::RegressionStore::st;
    our @ISA = ('DBD::_::st');
    our $imp_data_size = 0;
    sub bind_param {
        my ($sth, @args) = @_;
        push @main::bind_calls, \@args;
        return 1;
    }
}

my $dbh = DBI->connect('dbi:RegressionStore:mock', '', '');
my $resultset = {sql => 'SELECT value', results => [[42]]};
$dbh->{mock_add_resultset} = $resultset;

ok(grep($_ eq 'mock_add_resultset', @store_calls),
    'direct handle assignment invokes the non-JDBC driver STORE');
is($dbh->{mock_add_resultset}, $resultset,
    'the non-JDBC driver can retain a direct-assignment attribute');
ok(tied(%$dbh), 'DBI exposes the outer handle independently of its tied backing hash');

my $sth = DBI::_new_sth($dbh, {Statement => 'SELECT ?'});
ok($sth->bind_param(1, 42),
    'non-JDBC statement binding succeeds through the driver method');
is_deeply([@{$bind_calls[0]}[0, 1]], [1, 42],
    'non-JDBC binding passes the parameter and value to the driver');

my $private = {nested => 1};
my $assigned = $dbh->{private_DBIxInterpolate} = $private;
is($assigned, $private,
    'DBI base STORE returns the assigned reference to the caller');
