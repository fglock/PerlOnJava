use strict;
use warnings;
use feature 'say';

{
    # Create a test file
    my $filename = 'testfile.txt';
    open my $fh, '>', $filename or die "Cannot open $filename: $!";
    print $fh "This is a test file with some content.";
    close $fh;

    # Truncate the file to 10 bytes
    my $length = 10;
    my $result = truncate( $filename, $length );

    # Check if the truncation was successful
    if ($result) {
        open my $fh, '<', $filename or die "Cannot open $filename: $!";
        my $content = readline($fh);
        ## read $fh, $content, $length;
        close $fh;
        print "not "
          unless $content eq "This is a ";    # Expecting the first 10 bytes
        say "ok # Truncate by filename";
    }
    else {
        say "not ok # Truncate by filename";
    }

    # Clean up
    unlink $filename;
}

## truncate file handle

{
    # Create a test file
    my $filename = 'testfile.txt';
    open my $fh, '+>', $filename or die "Cannot open $filename: $!";
    print $fh "This is a test file with some content.";
    seek $fh, 0, 0;    # Reset file pointer to the beginning

    # Truncate the file to 10 bytes using file handle
    my $length = 10;
    my $result = truncate( $fh, $length );

    # Check if the truncation was successful
    if ($result) {
        seek $fh, 0, 0;    # Reset file pointer to the beginning
        my $content = readline($fh);
        ## read $fh, $content, $length;
        print "not "
          unless $content eq "This is a ";    # Expecting the first 10 bytes
        say "ok # Truncate by file handle";
    }
    else {
        say "not ok # Truncate by file handle";
    }

    # Clean up
    close $fh;
    unlink $filename;
}
