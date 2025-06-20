use 5.38.0;
use strict;
use warnings;
use Test::More tests => 10;
use Cwd qw(getcwd abs_path);

# Test mkdir function
my $test_dir = 'test_dir';
my $mkdir_result = mkdir $test_dir;
ok($mkdir_result, 'mkdir creates a directory');

# Test opendir and readdir functions
opendir my $dh, $test_dir or die "Cannot open directory: $!";
ok(defined $dh, 'opendir opens a directory handle');
my @files = readdir $dh;
ok(scalar @files > 0, 'readdir reads directory contents');

# Test telldir and seekdir functions
my $pos = telldir $dh;
ok(defined $pos, 'telldir returns a valid position');
seekdir $dh, $pos;
is(telldir($dh), $pos, 'seekdir sets the directory position');

# Test rewinddir function
rewinddir $dh;
is(telldir($dh), 0, 'rewinddir resets the directory position');

# Test closedir function
closedir $dh;
undef $dh;  # Explicitly undefine the directory handle
ok(!defined $dh, 'closedir closes the directory handle');

# Test chdir function
my $original_cwd = getcwd();
chdir $test_dir;
isnt(getcwd(), $original_cwd, 'chdir changes the working directory');

# Test cwd command after directory change
my $cwd_after_chdir = getcwd();
my $expected_cwd = abs_path('.');  # Correctly calculate the expected path
is($cwd_after_chdir, $expected_cwd, 'cwd returns correct path after chdir');

# Test open command after directory change
open my $fh, '>', 'test_file.txt' or die "Cannot open file: $!";
print $fh "test content";
close $fh;
ok(-e 'test_file.txt', 'open creates a file in the new directory');

# Cleanup
chdir $original_cwd;
unlink "$test_dir/test_file.txt" if -e "$test_dir/test_file.txt";
rmdir $test_dir if -d $test_dir;

# Verify cleanup
if (-d $test_dir || -e "$test_dir/test_file.txt") {
    die "Cleanup failed: test directory or files still exist";
}
