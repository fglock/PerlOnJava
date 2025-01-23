use feature 'say';
use strict;

###################
# Perl Directory Operations Tests

# Directory path for testing
my $test_dir = 'test_directory';

# Create a test directory and some files
mkdir $test_dir unless -d $test_dir;
open my $fh1, '>', "$test_dir/file1.txt";
close $fh1;
open my $fh2, '>', "$test_dir/file2.txt";
close $fh2;
open my $fh3, '>', "$test_dir/file3.txt";
close $fh3;

# Test opendir
opendir(my $dh, $test_dir) or die "Cannot open directory: $!";
print "not " unless defined $dh;
say "ok # opendir";

# Test readdir
my @files = readdir($dh);
print "not " unless @files >= 3; # . and .. are also included
say "ok # readdir <@files>";

# Test telldir
my $pos = telldir($dh);
print "not " unless defined $pos;
say "ok # telldir <$pos>";

## # Test seekdir
## seekdir($dh, $pos);
## my $file = readdir($dh);
## print "not " unless defined $file;
## say "ok # seekdir";

# Test rewinddir
rewinddir($dh);
@files = readdir($dh);
print "not " unless @files >= 3; # . and .. are also included
say "ok # rewinddir";

# Test closedir
my $res = closedir($dh);
print "not " if !defined $res;
say "ok # closedir <$res>";

# Cleanup
unlink "$test_dir/file1.txt";
unlink "$test_dir/file2.txt";
unlink "$test_dir/file3.txt";
rmdir $test_dir;

