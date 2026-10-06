use strict;
use warnings;
use Test::More;
use Cwd qw(getcwd abs_path);
use File::Temp qw(tempdir);

plan skip_all => 'Windows does not support chdir on directory handles'
    if $^O eq 'MSWin32';

my $original = getcwd();
my $root = tempdir(CLEANUP => 1);
my $target = "$root/target";
mkdir $target or die "mkdir $target: $!";
$root = abs_path($root);
$target = abs_path($target);

opendir my $dh, $root or die "opendir $root: $!";
ok(chdir($dh), 'chdir accepts an opened directory handle');
is(getcwd(), $root, 'chdir uses the directory captured by opendir');

ok(chdir($target), 'path chdir still works after directory-handle chdir');
is(getcwd(), $target, 'path chdir updates the current directory');

closedir $dh or die "closedir: $!";
chdir $original or die "chdir $original: $!";

done_testing();
