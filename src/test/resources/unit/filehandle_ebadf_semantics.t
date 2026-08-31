use strict;
use warnings;
use Test::More tests => 39;
use Cwd ();
use Errno ();
use File::Spec;
use File::Temp qw(tempdir);

# Perl reports operations on an invalid file descriptor by failing the operator
# and setting $! to EBADF, and it accepts a filehandle or directory handle for
# chdir() and chmod().  PerlOnJava either succeeded silently, threw a Java-level
# error, or left $! numerically empty, so wrapper modules that read $!
# (IO::Die reports it as OS_ERROR / EXTENDED_OS_ERROR) could not tell what had
# gone wrong.  See GitHub issue #1187.

my $EBADF = Errno::EBADF();

# Every failure below is one Perl warns about ("read() on closed filehandle",
# "Filehandle ... opened only for output", "closedir() attempted on invalid
# dirhandle", ...).  The wording is not what is under test, so keep the warnings
# out of the TAP stream.
my @warnings;
$SIG{__WARN__} = sub { push @warnings, $_[0] };

my $dir  = tempdir(CLEANUP => 1);
my $path = File::Spec->catfile($dir, 'data.txt');
open(my $seed, '>', $path) or die "open: $!";
print {$seed} "hello world\n";
close $seed;

sub closed_read_handle {
    open(my $fh, '<', $path) or die "open: $!";
    close $fh;
    return $fh;
}

# ---------------------------------------------------------------- read/sysread

{
    my $buf = 'unchanged';
    $! = 0;
    my $ret = read(STDOUT, $buf, 10);
    ok(!defined($ret), 'read() on STDOUT fails');
    is($! + 0, $EBADF, '...and sets $! to EBADF');
    ok(length("$!"), '...and $! stringifies to the EBADF message');
}

{
    my $buf;
    $! = 0;
    my $ret = read(closed_read_handle(), $buf, 10);
    ok(!defined($ret), 'read() on a closed handle fails');
    is($! + 0, $EBADF, '...and sets $! to EBADF');
}

{
    open(my $out, '>', File::Spec->catfile($dir, 'wo.txt')) or die "open: $!";
    my $buf;
    $! = 0;
    my $ret = read($out, $buf, 10);
    ok(!defined($ret), 'read() on a write-only handle fails');
    is($! + 0, $EBADF, '...and sets $! to EBADF');
    $! = 0;
    my $line = <$out>;
    ok(!defined($line), 'readline on a write-only handle fails');
    is($! + 0, $EBADF, '...and sets $! to EBADF');
    close $out;
}

{
    $! = 0;
    my $buf;
    my $ret = sysread(STDOUT, $buf, 10);
    ok(!defined($ret), 'sysread() on STDOUT fails');
    is($! + 0, $EBADF, '...and sets $! to EBADF');
}

# A readable handle still reads, and reading it does not report an error.
{
    open(my $in, '<', $path) or die "open: $!";
    my $buf;
    is(read($in, $buf, 5), 5, 'read() on a readable handle still works');
    is($buf, 'hello', '...and fills the buffer');
    close $in;
}

# ------------------------------------------------------------------- binmode

{
    $! = 0;
    my $ok = binmode(closed_read_handle());
    ok(!$ok, 'binmode() on a closed handle fails');
    is($! + 0, $EBADF, '...and sets $! to EBADF');
}

{
    open(my $in, '<', $path) or die "open: $!";
    ok(binmode($in), 'binmode() on an open handle still succeeds');
    ok(binmode($in, ':raw'), 'binmode() with an explicit layer still succeeds');
    close $in;
}

# --------------------------------------------------------- close/seek/tell

{
    $! = 0;
    my $fh = closed_read_handle();
    ok(!close($fh), 'close() on an already closed handle fails');
    is($! + 0, $EBADF, '...and sets $! to EBADF');
}

{
    $! = 0;
    ok(!seek(closed_read_handle(), 0, 0), 'seek() on a closed handle fails');
    is($! + 0, $EBADF, '...and sets $! to EBADF');
}

# --------------------------------------------------------------- closedir

{
    opendir(my $dh, $dir) or die "opendir: $!";
    ok(closedir($dh), 'closedir() on an open dirhandle succeeds');

    $! = 0;
    my $again = closedir($dh);
    ok(!$again, 'closedir() on an already closed dirhandle fails');
    is($! + 0, $EBADF, '...and sets $! to EBADF');
    like("$!", qr/\S/, '...and $! carries the EBADF message');
}

{
    opendir(my $dh, $dir) or die "opendir: $!";
    closedir($dh);
    $! = 0;
    my $entry = readdir($dh);
    ok(!$entry, 'readdir() on a closed dirhandle yields nothing');
    is($! + 0, $EBADF, '...and sets $! to EBADF');
}

# ------------------------------------------------------------------- chmod

SKIP: {
    skip 'POSIX permissions', 5 if $^O eq 'MSWin32';

    my $target = File::Spec->catfile($dir, 'chmod.txt');
    open(my $fh, '>', $target) or die "open: $!";

    is(chmod(0640, $fh), 1, 'chmod() accepts a filehandle');
    is(0777 & ((stat $target)[2]), 0640, '...and the permissions changed');

    # stat(FILEHANDLE) is fstat-like: it reports the inode's current mode, not
    # the mode the descriptor was opened with.
    is(0777 & ((stat $fh)[2]), 0640, 'stat(FILEHANDLE) sees the new mode');

    close $fh;
    $! = 0;
    is(chmod(0600, $fh), 0, 'chmod() on a closed filehandle changes nothing');

    # A missing pathname must report ENOENT, which is what a wrapper reads back.
    $! = 0;
    chmod(0600, File::Spec->catfile($dir, 'not_there'));
    is($! + 0, Errno::ENOENT(), 'chmod() on a missing path reports ENOENT');
}

# ------------------------------------------------------------------- chdir

{
    my $origin = Cwd::getcwd();
    my $real   = Cwd::abs_path($dir);

    opendir(my $dh, $dir) or die "opendir: $!";
    ok(chdir($dh), 'chdir() accepts a directory handle');
    is(Cwd::abs_path(Cwd::getcwd()), $real, '...and really changed directory');
    closedir($dh);

    chdir($origin) or die "chdir back: $!";

    # Perl also accepts a filehandle opened on a directory.
    if (open(my $fh, '<', $dir)) {
        ok(chdir($fh), 'chdir() accepts a filehandle opened on a directory');
        is(Cwd::abs_path(Cwd::getcwd()), $real, '...and really changed directory');
        close $fh;
    }
    else {
        ok(1, 'chdir() accepts a filehandle opened on a directory (skipped)');
        ok(1, '...and really changed directory (skipped)');
    }

    chdir($origin) or die "chdir back: $!";

    opendir(my $closed, $dir) or die "opendir: $!";
    closedir($closed);
    $! = 0;
    ok(!chdir($closed), 'chdir() on a closed dirhandle fails');
    is($! + 0, $EBADF, '...and sets $! to EBADF');

    is(Cwd::abs_path(Cwd::getcwd()), Cwd::abs_path($origin),
        'a failed chdir() leaves the working directory alone');
}
