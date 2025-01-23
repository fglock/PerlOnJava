use strict;
use warnings;
use feature 'say';

###################
# Perl File Operations Tests

# File paths for testing
my $test_file = 'test_file.txt';
my $test_file2 = 'test_file2.txt';

# Create test files and write some content
open my $fh, '>', $test_file or die "Cannot open file: $!";
print $fh "Line 1\nLine 2\nLine 3\n";
close $fh;

open $fh, '>', $test_file2 or die "Cannot open file: $!";
print $fh "File2 Line 1\nFile2 Line 2\n";
close $fh;

# Test <>
my @files = ($test_file);
my @lines = do { @ARGV = @files; <> };
print "not " unless @lines == 3 && $lines[1] eq "Line 2\n";
say "ok # <> operator";

# Test <<>>
@lines = do { @ARGV = @files; <<>> };
print "not " unless @lines == 3 && $lines[2] eq "Line 3\n";
say "ok # <<>> operator";

# Test <$fh>
open $fh, '<', $test_file or die "Cannot open file: $!";
@lines = <$fh>;
print "not " unless @lines == 3 && $lines[0] eq "Line 1\n";
say "ok # <\$fh> operator";
close $fh;

# Test <*.*>
@lines = <*.txt>;
print "not " unless @lines >= 2;  # Should at least include our two test files
say "ok # <*.*> operator";

# Test reading one line at a time
@files = ($test_file, $test_file2);
open my $file_handle, '<', $files[0] or die "Cannot open $files[0]: $!";
my $line = <$file_handle>;
print "not " unless $line eq "Line 1\n";
say "ok # single line read (first file)";

$line = <$file_handle>;
print "not " unless $line eq "Line 2\n";
say "ok # single line read (first file, second line)";

# Read remaining lines of first file
while (<$file_handle>) {
    last if eof($file_handle);
}
close $file_handle;

open $file_handle, '<', $files[1] or die "Cannot open $files[1]: $!";
$line = <$file_handle>;
print "not " unless $line eq "File2 Line 1\n";
say "ok # single line read (second file)";

# Test eof()
print "not " if eof($file_handle);  # Should not be eof yet
say "ok # eof() (mid-read)";

while (<$file_handle>) {} # Read remaining lines
close $file_handle;

print "not " unless eof($file_handle);  # Should be eof now
say "ok # eof() (after reading all)";

# Cleanup
unlink $test_file or warn "Could not unlink $test_file: $!";
unlink $test_file2 or warn "Could not unlink $test_file2: $!";
