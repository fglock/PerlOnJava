use strict;
use warnings;
use Test::More tests => 1;
use DBI;

$INC{'DBD/RegressionSimpleMock.pm'} = __FILE__;

{
    package DBD::RegressionSimpleMock;
    our $drh;
    sub driver {
        return $drh if $drh;
        ($drh) = DBI::_new_drh('DBD::RegressionSimpleMock::dr', {
            Name => 'RegressionSimpleMock',
        });
        return $drh;
    }
}
{
    package DBD::RegressionSimpleMock::dr;
    our @ISA = ('DBD::_::dr');
    our $imp_data_size = 0;
    sub connect {
        my $drh = shift;
        return $drh->SUPER::connect(@_);
    }
}
{
    package DBD::RegressionSimpleMock::db;
    our @ISA = ('DBD::_::db');
    our $imp_data_size = 0;
    sub disconnect { return 1 }
    sub STORE {
        my ($dbh, $key, $value) = @_;
        $dbh->{$key} = $value;
        return 1;
    }
}

my $dbh = DBI->connect('dbi:RegressionSimpleMock:mock-database', 'user', 'password');
ok($dbh && ref($dbh) eq 'DBI::db',
    'a SimpleMock-shaped SUPER::connect returns a DBI database handle');
