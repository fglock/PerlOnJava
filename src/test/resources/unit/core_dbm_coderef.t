use strict;
use warnings;
use File::Temp qw(tempdir);
use Test::More tests => 5;

my $dbmopen = \&CORE::dbmopen;
my $dbmclose = \&CORE::dbmclose;

eval { $dbmclose->([]) };
like($@, qr/Type of arg 1 to \&CORE::dbmclose must be hash reference/,
    'CORE::dbmclose code reference checks for a hash reference');
eval { $dbmopen->([], 'invalid.db', 0666) };
like($@, qr/Type of arg 1 to \&CORE::dbmopen must be hash reference/,
    'CORE::dbmopen code reference checks for a hash reference');

SKIP: {
    skip 'AnyDBM_File is unavailable in this standard Perl build', 3
        unless eval { require AnyDBM_File; 1 };

    my $directory = tempdir(CLEANUP => 1);
    my %db;
    ok($dbmopen->(\%db, "$directory/test", 0666),
        'CORE::dbmopen code reference opens the database');
    $db{answer} = 42;
    is($db{answer}, 42, 'CORE::dbmopen code reference ties the hash');
    ok($dbmclose->(\%db), 'CORE::dbmclose code reference unties the hash');
}
