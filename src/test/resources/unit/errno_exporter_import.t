our $ENOENT_KNOWN_BEFORE_IMPORT;
BEGIN {
    $ENOENT_KNOWN_BEFORE_IMPORT = exists $!{ENOENT};
}

use strict;
use warnings;
use Test::More;
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);

my $dir = tempdir(CLEANUP => 1);
my $module_path = File::Spec->catfile($dir, qw(IPC Run.pm));
make_path(File::Spec->catdir($dir, 'IPC'));
open my $module, '>', $module_path or die "Cannot write $module_path: $!";
print {$module} <<'MODULE';
package IPC::Run;
use bytes;
use Exporter;
use Fcntl;
use POSIX ();
use Carp;
use File::Spec ();
use IO::Handle;
use vars qw($_EIO $_EAGAIN);
use Errno qw(EIO EAGAIN EPIPE);

BEGIN {
    local $!;
    $! = EIO;
    $_EIO = qr/^$!/;
    $! = EAGAIN;
    $_EAGAIN = qr/^$!/;
}

sub issue1662_imported_errno_values { return EIO(), EAGAIN(), EPIPE() }
sub issue1662_errno_patterns { return $_EIO, $_EAGAIN }
1;
MODULE
close $module or die "Cannot close $module_path: $!";

ok($ENOENT_KNOWN_BEFORE_IMPORT, 'errno hash recognizes ENOENT before loading Errno');

{
    local @INC = ($dir, @INC);
    require IPC::Run;
}

ok($INC{'Errno.pm'} ne 'builtin', 'explicit require upgrades the builtin Errno marker');
is_deeply(
    [IPC::Run::issue1662_imported_errno_values()],
    [Errno::EIO(), Errno::EAGAIN(), Errno::EPIPE()],
    'IPC::Run-style runtime module imports errno constants',
);

my @patterns = IPC::Run::issue1662_errno_patterns();
{
    local $!;
    $! = Errno::EIO();
    like("$!", $patterns[0], 'EIO constant is available in BEGIN');
    $! = Errno::EAGAIN();
    like("$!", $patterns[1], 'EAGAIN constant is available in BEGIN');
}

done_testing;
