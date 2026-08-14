use strict;
use warnings;

use DBI;
use File::Temp qw(tempfile);
use Scalar::Util qw(refaddr);
use Test::More tests => 10;
use threads;

my ($file, $path) = tempfile();
close $file;

my $dsn = "dbi:SQLite:dbname=$path";
my $dbh = DBI->connect($dsn, '', '', {
    RaiseError          => 1,
    PrintError          => 0,
    AutoInactiveDestroy => 1,
});
$dbh->do('create table ownership_test (value integer)');
$dbh->do('insert into ownership_test values (1)');
my $sth = $dbh->prepare('select value from ownership_test order by value');

my $thread = threads->create(sub {
    my ($inherited_dbh, $inherited_sth, $thread_dsn) = @_;

    my $dbh_ok = eval { $inherited_dbh->ping; 1 };
    my $dbh_error = $@;
    my $sth_ok = eval { $inherited_sth->execute; 1 };
    my $sth_error = $@;

    my $child_dbh = DBI->connect_cached($thread_dsn, '', '', {
        RaiseError => 1,
        PrintError => 0,
    });
    my $cached_again = DBI->connect_cached($thread_dsn, '', '', {
        RaiseError => 1,
        PrintError => 0,
    });
    my $cache_is_local = refaddr($child_dbh) == refaddr($cached_again);

    $child_dbh->begin_work;
    $child_dbh->do('insert into ownership_test values (2)');
    $child_dbh->commit;
    my ($child_count) = $child_dbh->selectrow_array(
        'select count(*) from ownership_test');
    $child_dbh->disconnect;

    return {
        dbh_rejected   => !$dbh_ok && $dbh_error =~ /owned by thread/i,
        sth_rejected   => !$sth_ok && $sth_error =~ /owned by thread/i,
        cache_is_local => $cache_is_local,
        child_count    => $child_count,
    };
}, $dbh, $sth, $dsn);

my $child = $thread->join;
ok($child->{dbh_rejected}, 'child cannot use an inherited database handle');
ok($child->{sth_rejected}, 'child cannot use an inherited statement handle');
ok($child->{cache_is_local}, 'connect_cached cache is local to the child runtime');
is($child->{child_count}, 2, 'child-owned connection executes and commits normally');

ok($dbh->ping, 'destroying inherited child handle does not disconnect parent');
ok($sth->execute, 'parent statement remains usable after child exit');
is_deeply($sth->fetchall_arrayref, [[1], [2]],
    'parent statement observes committed child transaction');
is($dbh->{Active}, 1, 'parent handle remains active');
$dbh->disconnect;
ok(!$dbh->{Active}, 'parent still controls its connection lifetime');
ok(unlink($path) || !-e $path, 'temporary database is removed');
