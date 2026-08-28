use strict;
use warnings;
use Test::More;

BEGIN {
    eval { require DBD::Pg; 1 }
        or plan skip_all => 'DBD::Pg is not available';
}

is(
    DBD::Pg->_dsn_to_jdbc('dbname=app;host=db.example;port=5433'),
    'jdbc:postgresql://db.example:5433/app',
    'translates a standard PostgreSQL DSN',
);
is(
    DBD::Pg->_dsn_to_jdbc('database=app;sslmode=require;connect_timeout=5'),
    'jdbc:postgresql://localhost/app?sslmode=require&connect_timeout=5',
    'translates aliases and JDBC connection properties',
);
is(
    DBD::Pg->_dsn_to_jdbc('app'),
    'jdbc:postgresql://localhost/app',
    'translates the short database-name form',
);
ok(DBD::Pg::dr->isa('DBD::JDBC::dr'), 'driver handle inherits JDBC backend');
ok(DBD::Pg::db->isa('DBD::JDBC::db'), 'database handle inherits JDBC backend');
ok(DBD::Pg::st->isa('DBD::JDBC::st'), 'statement handle inherits JDBC backend');

done_testing;
