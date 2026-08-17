use strict;
use warnings;
use Fcntl qw(O_CREAT O_RDWR LOCK_EX LOCK_NB LOCK_SH);
use File::Temp qw(tempdir);
use Test::More tests => 2;

my $dir = tempdir(CLEANUP => 1);
my $path = "$dir/lock";
our $lock_fh;

ok(sysopen($lock_fh, $path, O_CREAT | O_RDWR) && flock($lock_fh, LOCK_SH | LOCK_NB),
    'first handle obtains a shared lock');

# Replacing the last reference to a scalar filehandle closes its old open file
# description.  The replacement can therefore upgrade the process-local lock.
sysopen($lock_fh, $path, O_CREAT | O_RDWR) or die "reopen $path: $!";
ok(flock($lock_fh, LOCK_EX | LOCK_NB),
    'reassigned handle can obtain an exclusive lock');

close($lock_fh);
