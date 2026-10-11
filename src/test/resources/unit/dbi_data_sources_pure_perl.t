use strict;
use warnings;
use Test::More tests => 2;
use DBI;
use DBD::ExampleP;

$INC{'DBD/NoDataSources.pm'} = __FILE__;
{
    package DBD::NoDataSources;
    our $drh;
    sub driver {
        return $drh if $drh;
        ($drh) = DBI::_new_drh('DBD::NoDataSources::dr', {
            Name => 'NoDataSources',
        });
        return $drh;
    }
}
{
    package DBD::NoDataSources::dr;
    our $imp_data_size = 0;
}
{
    package DBD::NoDataSources::db;
    our $imp_data_size = 0;
}
{
    package DBD::NoDataSources::st;
    our $imp_data_size = 0;
}

is_deeply(
    [DBI->data_sources('ExampleP')],
    ['dbi:ExampleP:dir=.'],
    'data_sources dispatches to a pure-Perl driver',
);
is_deeply(
    [DBI->data_sources('NoDataSources')],
    [],
    'a driver without data_sources falls back to JDBC discovery',
);
