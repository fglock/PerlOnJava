use strict;
use warnings;
use File::Temp qw(tempfile);
use Test::More;

my ($fh, $path) = tempfile();
close $fh or die "close tempfile: $!";
unlink $path or die "unlink tempfile: $!";

my %missing;
my $missing_error;
{
    local @INC = ();
    local $@;
    eval { dbmopen(%missing, "$path-missing", 0666); 1 }
        or $missing_error = $@;
}
like($missing_error, qr/No dbm on this machine/, 'dbmopen fails when DBM modules are unavailable');

my %db;
ok(dbmopen(%db, $path, 0666), 'dbmopen creates a PerlOnJava DBM file');
$db{alpha} = 'one';
$db{binary} = "zero\x00byte";
ok(exists $db{alpha}, 'DBM key exists after STORE');
is($db{alpha}, 'one', 'DBM FETCH returns stored value');
is($db{binary}, "zero\x00byte", 'DBM preserves embedded NUL');
is_deeply([sort keys %db], [qw(alpha binary)], 'DBM FIRSTKEY/NEXTKEY iterate keys');
delete $db{alpha};
ok(1, 'DBM DELETE completes');
ok(!exists $db{alpha}, 'DBM DELETE removes key');
ok(dbmclose(%db), 'dbmclose closes the database');

my %reopened;
ok(dbmopen(%reopened, $path, 0), 'dbmopen reopens an existing database');
is($reopened{binary}, "zero\x00byte", 'reopened DBM returns persisted value');
ok(dbmclose(%reopened), 'dbmclose closes reopened database');

my $warnings = 0;
{
    local $^W = 1;
    local $SIG{__WARN__} = sub { ++$warnings };
    my %warn_db;
    dbmopen(%warn_db, "$path-warn", undef);
    dbmclose(%warn_db) if tied(%warn_db);
}
is($warnings, 1, 'dbmopen warns for an undefined mode');
unlink grep { -e $_ } glob "$path-warn*";

unlink grep { -e $_ } glob "$path*";
done_testing();
