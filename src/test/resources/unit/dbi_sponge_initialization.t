use strict;
use warnings;
use Test::More tests => 7;
use DBI;

my $dbh = DBI->connect('dbi:Sponge:', '', '', {
    RaiseError => 1,
    PrintError => 0,
});
ok($dbh, 'connect through dbi:Sponge initializes the driver');

is_deeply(
    $dbh->sponge_test_installed_method('one', 'two'),
    ['one', 'two'],
    'driver-installed database method is available after connection',
);

my $sth = $dbh->prepare('SELECT value', { rows => [[42]] });
ok($sth, 'Sponge prepares a statement with supplied rows');
is_deeply($sth->fetchrow_arrayref, [42], 'Sponge fetches supplied row');

$dbh->disconnect;
ok(!$dbh->{Active}, 'Sponge disconnect deactivates the handle');
my $reconnected = DBI->connect('dbi:Sponge:', '', '', {
    RaiseError => 1,
    PrintError => 0,
});
ok($reconnected, 'Sponge reconnect initializes the driver');
$reconnected->disconnect;
ok(!$reconnected->{Active}, 'Sponge reconnected handle disconnects');
