use feature 'say';
use strict;

###################
# Perl I/O Operations Tests

# Test reading from STDIN
print "Enter your name: ";
my $name = readline(STDIN);
print "not " if !defined $name || $name eq '';
say "ok # Read from STDIN";

# Test writing to STDOUT
print "Hello, World!\n";
say "ok # Write to STDOUT";

# Test writing to STDERR
print STDERR "This is an error message\n";
say "ok # Write to STDERR";

# Test file I/O
my $filename = "testfile.txt";
open my $fh, '>', $filename or die "Cannot open file: $!";
print $fh "This is a test file\n";
close $fh;
say "ok # Write to file";

open $fh, '<', $filename or die "Cannot open file: $!";
my $line = readline($fh);
print "not " if $line ne "This is a test file\n";
close $fh;
say "ok # Read from file <$!>";

# Clean up
unlink $filename;

