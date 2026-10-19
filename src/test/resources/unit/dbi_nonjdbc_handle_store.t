use strict;
use warnings;
use DBI;
use Test::More tests => 3;

our @store_calls;
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
        $dbh->{$key} = $value;
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
