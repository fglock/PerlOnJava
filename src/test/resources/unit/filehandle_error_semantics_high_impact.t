use strict;
use warnings;
use Test::More tests => 16;
use Errno qw(EBADF);
use Cwd qw(getcwd);
use File::Temp qw(tempfile);

my $cwd = getcwd();
open my $directory_file, '<', '.' or die $!;
ok(chdir($directory_file), 'chdir accepts an open directory filehandle');
is(getcwd(), $cwd, 'handle chdir selects the opened directory');
close $directory_file;
ok(!chdir($directory_file), 'chdir fails on a closed directory filehandle');
is(0 + $!, EBADF, 'closed handle chdir sets EBADF');

my ($file, $path) = tempfile();
ok(chmod(0640, $file), 'chmod accepts an open filehandle');
is((stat($path))[2] & 0777, 0640, 'handle chmod changes the opened file');
close $file;

ok(!binmode($file), 'binmode fails on a closed handle');
is(0 + $!, EBADF, 'closed binmode sets EBADF');
ok(!close($file), 'a second close fails');
is(0 + $!, EBADF, 'a second close sets EBADF');
is(chmod(0600, $file), 0, 'chmod fails on a closed filehandle');
is(0 + $!, EBADF, 'closed handle chmod sets EBADF');

my $buffer = '';
my $read_result = read(STDOUT, $buffer, 1);
ok(!defined($read_result) && 0 + $! == EBADF,
    'read on write-only STDOUT returns undef with EBADF');

opendir my $directory_handle, '.' or die $!;
closedir $directory_handle;
my $closedir_result = closedir($directory_handle);
ok(!defined($closedir_result), 'closedir fails on a closed directory handle');
is(0 + $!, EBADF, 'closedir sets EBADF');

my $data = 'hello';
open my $scalar_file, '<', \$data or die $!;
ok(UNIVERSAL::isa($scalar_file, 'GLOB'),
    'scalar-backed filehandle is recognized as a GLOB');
