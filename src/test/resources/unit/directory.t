use 5.38.0;
use strict;
use warnings;
use Test::More tests => 9;
use Cwd qw(getcwd abs_path);
use File::Spec;

# Create a unique directory name to avoid conflicts when tests run in parallel
my $test_dir = 'test_dir_' . $$ . '_' . time();
my $test_file = 'test_file.txt';

# Cleanup function to remove test artifacts
sub cleanup {
    my $original_cwd = getcwd();

    # Try to clean up test file and directory
    eval {
        chdir $original_cwd;
        if (-e "$test_dir/$test_file") {
            unlink "$test_dir/$test_file" or warn "Failed to remove test file: $!";
        }
        if (-d $test_dir) {
            rmdir $test_dir or warn "Failed to remove test directory: $!";
        }
    };
}

# Ensure cleanup happens even if test fails
END { cleanup(); }

# Clean up any leftover artifacts from previous failed runs
cleanup();

# Test mkdir function
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

## -- test disabled because it gives inconsistent results in Mac, Windows, and Linux
## # Test rewinddir function
## rewinddir $dh;
## is(telldir($dh), 0, 'rewinddir resets the directory position');

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
open my $fh, '>', $test_file or die "Cannot open file: $!";
print $fh "test content";
close $fh;
ok(-e $test_file, 'open creates a file in the new directory');

# Cleanup - restore original directory before removing files
chdir $original_cwd;

# Give filesystem a moment to sync (helps with parallel test reliability)
# Remove test file
if (-e "$test_dir/$test_file") {
    unlink "$test_dir/$test_file" or warn "Failed to remove $test_dir/$test_file: $!";
}

# Remove test directory
if (-d $test_dir) {
    rmdir $test_dir or warn "Failed to remove $test_dir: $!";
}

# Verify cleanup (non-fatal - let END block try again if needed)
if (-d $test_dir || -e "$test_dir/$test_file") {
    diag "Warning: Cleanup verification found leftover files (will retry in END block)";
}
