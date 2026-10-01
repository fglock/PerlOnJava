use strict;
use warnings;
use Test::More;
use Cwd qw(getcwd abs_path);
use Config;
use Errno qw(EBADF);
use File::Temp qw(tempdir);

plan skip_all => 'Windows does not expose POSIX fchdir capabilities'
    if $^O eq 'MSWin32';

my $has_dirfd = ($Config{d_dirfd} || $Config{d_dir_dd_fd} || '') eq 'define';
plan tests => $has_dirfd ? 10 : 9;

my $original_dir = getcwd();
my $temporary_dir = tempdir(CLEANUP => 1);
my $target_dir = "$temporary_dir/op";
mkdir $target_dir or die "mkdir $target_dir: $!";
$temporary_dir = abs_path($temporary_dir);
$target_dir = abs_path($target_dir);

open my $file_handle, '<', $target_dir or die "open $target_dir: $!";
opendir my $directory_handle, $temporary_dir or die "opendir $temporary_dir: $!";
no warnings 'once';
*FH = $file_handle;
*DH = $directory_handle;

ok(chdir FH, 'chdir accepts a bareword filehandle alias');
is(getcwd(), $target_dir, 'bareword filehandle selects its opened directory');
if ($has_dirfd) {
    ok(chdir DH, 'chdir accepts a bareword directory-handle alias');
    is(getcwd(), $temporary_dir, 'bareword directory handle selects its directory');
} else {
    my $error = eval { chdir DH; 1 } ? '' : $@;
    like($error, qr/^The dirfd function is unimplemented at/,
        'chdir on a directory-handle alias reports unsupported dirfd');
    chdir '..' or die "return from $target_dir: $!";
}

close FH;
{
    my $warning;
    local $SIG{__WARN__} = sub { $warning = $_[0] };
    $! = 0;
    ok(!chdir(FH), 'chdir fails on a closed bareword filehandle');
    is(0 + $!, EBADF, 'closed bareword filehandle sets EBADF');
    like($warning, qr/on closed filehandle/, 'closed bareword filehandle warns');
}

{
    my $warning;
    local $SIG{__WARN__} = sub { $warning = $_[0] };
    $! = 0;
    ok(!chdir(NEVEROPENED), 'chdir fails on an unopened bareword filehandle');
    is(0 + $!, EBADF, 'unopened bareword filehandle sets EBADF');
    like($warning, qr/on unopened filehandle NEVEROPENED/, 'unopened bareword filehandle warns');
}

chdir $original_dir or die "chdir $original_dir: $!";
