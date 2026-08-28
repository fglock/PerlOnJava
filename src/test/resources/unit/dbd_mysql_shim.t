use strict;
use warnings;
use Test::More;

BEGIN {
    eval { require DBD::mysql; 1 }
        or plan skip_all => 'DBD::mysql shim unavailable';
}

is(
    DBD::mysql->_dsn_to_jdbc('database=orders;host=db.example;port=3307'),
    'jdbc:mysql://db.example:3307/orders',
    'maps named MySQL DSN attributes',
);
is(
    DBD::mysql->_dsn_to_jdbc('orders;host=db.example'),
    'jdbc:mysql://db.example:3306/orders',
    'maps positional database and default port',
);
is(
    DBD::mysql->_dsn_to_jdbc('host=db.example;dbname=orders'),
    'jdbc:mysql://db.example:3306/orders',
    'accepts dbname after host',
);
is(
    DBD::mysql->_dsn_to_jdbc(''),
    'jdbc:mysql://localhost:3306/',
    'uses MySQL defaults for an empty suffix',
);
is(
    DBD::mysql->_dsn_to_jdbc('jdbc:mysql://db.example/orders'),
    'jdbc:mysql://db.example/orders',
    'preserves an explicit JDBC URL',
);

ok(DBD::mysql->isa('DBD::JDBC'), 'driver inherits from DBD::JDBC');
ok(DBD::mysql::dr->isa('DBD::JDBC::dr'), 'database driver inherits JDBC driver');
ok(DBD::mysql::db->isa('DBD::JDBC::db'), 'database handle inherits JDBC handle');
ok(DBD::mysql::st->isa('DBD::JDBC::st'), 'statement handle inherits JDBC statement');

done_testing;
