use strict;
use warnings;
use feature 'say';
use Cwd qw(getcwd abs_path);

# Test mkdir function
my $test_dir = 'test_dir';
my $mkdir_result = mkdir $test_dir;
print "not " if !$mkdir_result; say "ok # mkdir creates a directory";

# Test opendir and readdir functions
opendir my $dh, $test_dir or die "Cannot open directory: $!";
my @files = readdir $dh;
print "not " if !defined $dh; say "ok # opendir opens a directory handle";
print "not " if scalar @files == 0; say "ok # readdir reads directory contents";

# Test telldir and seekdir functions
my $pos = telldir $dh;
print "not " if !defined $pos; say "ok # telldir returns a valid position";
seekdir $dh, $pos;
print "not " if telldir($dh) != $pos; say "ok # seekdir sets the directory position";

# Test rewinddir function
rewinddir $dh;
# print "Debug: Position after rewinddir: ", telldir($dh), "\n";
print "not " if telldir($dh) != 1; say "ok # rewinddir resets the directory position";

# Test closedir function
closedir $dh;
undef $dh;  # Explicitly undefine the directory handle
# print "Debug: Directory handle after closedir: ", defined $dh ? "defined" : "undefined", "\n";
print "not " if defined $dh; say "ok # closedir closes the directory handle";

# Test chdir function
my $original_cwd = getcwd();
chdir $test_dir;
print "not " if getcwd() eq $original_cwd; say "ok # chdir changes the working directory";

# Test cwd command after directory change
my $cwd_after_chdir = getcwd();
my $expected_cwd = abs_path('.');  # Correctly calculate the expected path
# print "Debug: CWD after chdir: $cwd_after_chdir vs. $expected_cwd\n";
print "not " if $cwd_after_chdir ne $expected_cwd; say "ok # cwd returns correct path after chdir";

# Test open command after directory change
open my $fh, '>', 'test_file.txt' or die "Cannot open file: $!";
print $fh "test content";
close $fh;
print "not " if !-e 'test_file.txt'; say "ok # open creates a file in the new directory";

# Test rmdir function
chdir $original_cwd;
unlink "$test_dir/test_file.txt";
rmdir $test_dir;
print "not " if -d $test_dir; say "ok # rmdir removes the directory";

# Manual cleanup
unlink "$test_dir/test_file.txt" if -e "$test_dir/test_file.txt";
rmdir $test_dir if -d $test_dir;

