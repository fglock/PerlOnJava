use strict;
use warnings;
use Test::More;
use DBI;
use DBD::SQLite;

my $dbh = DBI->connect(
    'dbi:SQLite:dbname=:memory:',
    '',
    '',
    { RaiseError => 1, PrintError => 0 },
);

$dbh->do('CREATE TABLE cookies (encrypted_value BLOB)');

# Chrome cookie databases can contain arbitrary ciphertext bytes in a TEXT
# storage value, even when the declared column type is BLOB.
my $encrypted = "v10" . ("\x83" x 64);
my $hex = unpack 'H*', $encrypted;
$dbh->do("INSERT INTO cookies VALUES (CAST(X'$hex' AS TEXT))");

my $array_row = $dbh->selectrow_arrayref('SELECT encrypted_value FROM cookies');
is(length($array_row->[0]), length($encrypted),
    'array fetch preserves the byte length of invalid UTF-8 TEXT');
is(unpack('H*', $array_row->[0]), $hex,
    'array fetch preserves invalid UTF-8 TEXT bytes');

my $hash_row = $dbh->selectrow_hashref('SELECT encrypted_value FROM cookies');
is(length($hash_row->{encrypted_value}), length($encrypted),
    'hash fetch preserves the byte length of invalid UTF-8 TEXT');
is(unpack('H*', $hash_row->{encrypted_value}), $hex,
    'hash fetch preserves invalid UTF-8 TEXT bytes');

$dbh->disconnect;
done_testing;
