use strict;
use warnings;
use Test::More tests => 14;
use DBI;
use DBD::ExampleP;

my $dbh = DBI->connect('dbi:ExampleP:dummy', '', '');
my $sth = $dbh->prepare('select name from .');
ok($sth, 'pure-Perl driver prepares a result statement');
ok(!$sth->{Active}, 'new pure-Perl statement starts inactive');

ok($sth->execute, 'pure-Perl result statement executes');
ok($sth->{Active}, 'executed pure-Perl result statement becomes active');
is($dbh->{ActiveKids}, 1, 'database handle counts active pure-Perl statements');
ok($sth->execute, 'active pure-Perl statement can be executed again');
is($dbh->{ActiveKids}, 1, 're-execution does not double count the active statement');
my $rows = $sth->fetchall_arrayref;
ok(ref($rows) eq 'ARRAY' && @$rows,
    'fetchall_arrayref retrieves rows from the active pure-Perl statement');
ok(!$sth->{Active}, 'exhausting the cursor marks the statement inactive');

$sth->finish;
ok(!$sth->{Active} && !$dbh->{ActiveKids}, 'finish clears active state and count');

my $update = $dbh->prepare('set examplep_value = 1');
ok(!$update->execute, 'pure-Perl non-result statement returns no rows');
ok(!$update->{Active} && !$dbh->{ActiveKids}, 'non-result execution stays inactive');

SKIP: {
    skip 'PerlOnJava DBI implementor dispatch is not present', 2
        unless defined &DBI::_dispatch_implementor_method;
    {
        no strict 'refs';
        @{'DBIRegression::NoExecute::st::ISA'} = ('DBI::st');
    }
    my $missing = { ImplementorClass => 'DBIRegression::NoExecute::st' };
    my $ok = eval { DBI::_dispatch_implementor_method($missing, 'execute'); 1 };
    ok(!$ok && $@ =~ /Can't locate object method "execute"/, 'missing driver method errors without recursive fallback');
    my $missing_disconnect = { ImplementorClass => 'DBIRegression::NoExecute::db' };
    $ok = eval { DBI::_dispatch_implementor_method($missing_disconnect, 'disconnect'); 1 };
    ok(!$ok && $@ =~ /Can't locate object method "disconnect"/, 'missing disconnect errors without recursive fallback');
}
