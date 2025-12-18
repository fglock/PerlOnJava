use strict;
use warnings;
use Test::More tests => 4;

# Test filehandle duplication with named filehandles (STDERR, STDOUT, STDIN)
# This is a regression test for the "Unsupported filehandle duplication: STDERR" error

subtest 'duplicate STDERR by name' => sub {
    plan tests => 2;
    
    # Save STDERR to a new filehandle
    open my $saveerr, ">&STDERR" or die "Cannot dup STDERR: $!";
    ok(defined $saveerr, 'Duplicated STDERR to lexical filehandle');
    
    # Write to the duplicated handle
    print $saveerr "";  # Just test that we can write without error
    ok(1, 'Can write to duplicated STDERR handle');
    
    close $saveerr;
};

subtest 'duplicate STDOUT by name' => sub {
    plan tests => 2;
    
    # Save STDOUT to a new filehandle
    open my $saveout, ">&STDOUT" or die "Cannot dup STDOUT: $!";
    ok(defined $saveout, 'Duplicated STDOUT to lexical filehandle');
    
    # Write to the duplicated handle
    print $saveout "";  # Just test that we can write without error
    ok(1, 'Can write to duplicated STDOUT handle');
    
    close $saveout;
};

subtest 'duplicate by file descriptor number' => sub {
    plan tests => 2;
    
    # Duplicate STDERR using file descriptor 2
    open my $saveerr, ">&2" or die "Cannot dup fd 2: $!";
    ok(defined $saveerr, 'Duplicated fd 2 (STDERR) to lexical filehandle');
    
    # Duplicate STDOUT using file descriptor 1
    open my $saveout, ">&1" or die "Cannot dup fd 1: $!";
    ok(defined $saveout, 'Duplicated fd 1 (STDOUT) to lexical filehandle');
    
    close $saveerr;
    close $saveout;
};

subtest 'redirect and restore STDERR' => sub {
    plan tests => 2;
    
    # This pattern is commonly used to temporarily suppress STDERR
    my $dummy = \*SAVEERR;  # avoid "used only once" warning
    
    # Save STDERR
    open SAVEERR, ">&STDERR" or die "Cannot save STDERR: $!";
    ok(1, 'Saved STDERR to bareword filehandle');
    
    # Restore STDERR
    close STDERR;
    open STDERR, ">&SAVEERR" or die "Cannot restore STDERR: $!";
    ok(1, 'Restored STDERR from saved handle');
    
    close SAVEERR;
};

1;
