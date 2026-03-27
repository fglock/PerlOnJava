#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;
use File::Temp qw(tempdir);

=head1 NAME

local_glob_filehandle.t - Tests for the C<do { local *FH; *FH }> pattern

=head1 DESCRIPTION

This test verifies that the classic Perl idiom for creating anonymous filehandles
works correctly:

    my $fh = do { local *FH; *FH; };

This pattern is widely used in CPAN modules (e.g., Log::Log4perl::Appender::File)
to create unique filehandle copies. Each call should return a glob with an
independent IO slot, so that opening files with different handles doesn't
cause them to share the same underlying file descriptor.

=head2 How the pattern works in Perl

1. C<local *FH> saves the current *FH glob and creates a new empty glob
   for the duration of the block
2. C<*FH> returns that localized glob value  
3. When the block ends, *FH is restored, but the returned value retains
   its own independent IO slot
4. Multiple calls create multiple independent filehandle copies

=head2 The bug

In PerlOnJava, the returned glob copies were sharing the same IO slot,
causing all filehandles to point to the last-opened file.

=cut

# Create a temporary directory for our test files
my $tmpdir = tempdir(CLEANUP => 1);

# Helper function that uses the classic pattern
sub make_fh {
    my $fh = do { local *FH; *FH; };
    return $fh;
}

subtest 'Basic local *FH pattern' => sub {
    my $fh = make_fh();
    ok(defined $fh, 'make_fh returns a defined value');
    is(ref(\$fh), 'GLOB', 'make_fh returns a glob');
    like("$fh", qr/\*main::FH/, 'glob stringifies to *main::FH');
};

subtest 'Multiple filehandles are independent - the core bug' => sub {
    # This is the critical test case from Log::Log4perl bug
    # See: Log::Log4perl::Appender::File line 124
    #   my $fh = do { local *FH; *FH; };
    
    my $file1 = "$tmpdir/test1.txt";
    my $file2 = "$tmpdir/test2.txt";
    
    # Create two independent filehandles
    my $fh1 = make_fh();
    my $fh2 = make_fh();
    
    # Both stringify the same way (this is expected and OK)
    is("$fh1", "$fh2", 'Both handles stringify to *main::FH (expected)');
    
    # Open different files
    open $fh1, ">", $file1 or die "Cannot open $file1: $!";
    open $fh2, ">", $file2 or die "Cannot open $file2: $!";
    
    # After opening both files, each should have its own fileno
    # This is where the bug manifests: in PerlOnJava both return undef
    # because the second open overwrites the first handle's IO slot
    my $fileno1 = fileno($fh1);
    my $fileno2 = fileno($fh2);
    
    ok(defined $fileno1, 'fh1 has a valid fileno after open');
    ok(defined $fileno2, 'fh2 has a valid fileno after open');
    
    if (defined $fileno1 && defined $fileno2) {
        isnt($fileno1, $fileno2, 'fh1 and fh2 have different fileno values');
    }
    
    # Write to each handle
    print $fh1 "Content for file1\n";
    print $fh2 "Content for file2\n";
    
    close $fh1;
    close $fh2;
    
    # Verify file contents
    my $content1 = do { open my $r, "<", $file1; local $/; <$r> };
    my $content2 = do { open my $r, "<", $file2; local $/; <$r> };
    
    is($content1, "Content for file1\n", 'file1 has correct content');
    is($content2, "Content for file2\n", 'file2 has correct content');
};

subtest 'Three independent filehandles' => sub {
    my $file1 = "$tmpdir/triple1.txt";
    my $file2 = "$tmpdir/triple2.txt";
    my $file3 = "$tmpdir/triple3.txt";
    
    my $fh1 = make_fh();
    my $fh2 = make_fh();
    my $fh3 = make_fh();
    
    open $fh1, ">", $file1 or die $!;
    open $fh2, ">", $file2 or die $!;
    open $fh3, ">", $file3 or die $!;
    
    print $fh1 "ONE\n";
    print $fh2 "TWO\n";
    print $fh3 "THREE\n";
    
    close $fh1;
    close $fh2;
    close $fh3;
    
    my $c1 = do { open my $r, "<", $file1; local $/; <$r> };
    my $c2 = do { open my $r, "<", $file2; local $/; <$r> };
    my $c3 = do { open my $r, "<", $file3; local $/; <$r> };
    
    is($c1, "ONE\n", 'First file has correct content');
    is($c2, "TWO\n", 'Second file has correct content');
    is($c3, "THREE\n", 'Third file has correct content');
};

subtest 'Filehandles survive local scope restoration' => sub {
    # This tests that the captured glob retains its IO even after
    # the local scope ends and *FH is restored to its original value
    
    my $file = "$tmpdir/scope_test.txt";
    my $fh;
    
    {
        # Inside this block, *FH is localized
        $fh = do { local *FH; *FH; };
        # The local scope of *FH ends here, but $fh should retain the IO
    }
    
    # Open file after the local scope has ended
    open $fh, ">", $file or die $!;
    print $fh "survived\n";
    close $fh;
    
    my $content = do { open my $r, "<", $file; local $/; <$r> };
    is($content, "survived\n", 'Filehandle works after local scope ends');
};

subtest 'Pattern used in object context (like Log::Log4perl)' => sub {
    # Simulate how Log::Log4perl uses this pattern
    package TestAppender {
        sub new {
            my ($class, $filename) = @_;
            my $self = { filename => $filename };
            bless $self, $class;
            
            # This is exactly what Log::Log4perl::Appender::File does
            my $fh = do { local *FH; *FH; };
            open $fh, ">", $filename or die "Cannot open $filename: $!";
            $self->{fh} = $fh;
            
            return $self;
        }
        
        sub log {
            my ($self, $msg) = @_;
            my $fh = $self->{fh};
            print $fh $msg;
        }
        
        sub close {
            my ($self) = @_;
            close $self->{fh} if $self->{fh};
        }
    }
    
    package main;
    
    my $file1 = "$tmpdir/appender1.log";
    my $file2 = "$tmpdir/appender2.log";
    
    my $app1 = TestAppender->new($file1);
    my $app2 = TestAppender->new($file2);
    
    $app1->log("Message to appender 1\n");
    $app2->log("Message to appender 2\n");
    
    $app1->close();
    $app2->close();
    
    my $c1 = do { open my $r, "<", $file1; local $/; <$r> };
    my $c2 = do { open my $r, "<", $file2; local $/; <$r> };
    
    is($c1, "Message to appender 1\n", 'First appender wrote to correct file');
    is($c2, "Message to appender 2\n", 'Second appender wrote to correct file');
};

done_testing();
