#!/usr/bin/env perl

use strict;
use warnings;
use File::Find;
use File::Slurp;
use File::Copy;
use Cwd;

# Configuration
my $test_dir = $ARGV[0] || '.';
my $backup_suffix = '.bak';
my $dry_run = grep { $_ eq '--dry-run' } @ARGV;
my $verbose = grep { $_ eq '--verbose' } @ARGV;

print "TAP Test Fixer - Adding test counts to old Perl tests\n";
print "Scanning directory: $test_dir\n";
print "Mode: " . ($dry_run ? "DRY RUN" : "LIVE") . "\n\n";

my @test_files;
my $original_dir = getcwd();

# Find all .t and .pl files that look like tests
find(sub {
    return unless -f $_;
    return unless /\.(t|pl)$/;
    
    my $file = $File::Find::name;
    
    # Read first few lines to see if it looks like a test
    open my $fh, '<', $_ or return;
    my $content = do { local $/; <$fh> };
    close $fh;
    
    # Skip if it looks like it might be a test file
    if ($content =~ /(?:ok|not ok|print.*ok|say.*ok)/i) {
        push @test_files, $file;
    }
}, $test_dir);

print "Found " . scalar(@test_files) . " potential test files\n\n";

foreach my $file (@test_files) {
    process_test_file($file);
}

print "\nProcessing complete!\n";

sub process_test_file {
    my $file = shift;
    
    print "Processing: $file\n" if $verbose;
    
    my $content = read_file($file);
    
    # Check if already uses Test::More or has a plan
    if ($content =~ /use\s+Test::More/i || $content =~ /^\s*1\.\.(\d+)/m) {
        print "  SKIP: Already uses Test::More or has TAP plan\n" if $verbose;
        return;
    }
    
    # Count test assertions by running the script and capturing output
    my $test_count = count_tests_by_execution($file);
    
    if ($test_count == 0) {
        print "  SKIP: No tests found or execution failed\n" if $verbose;
        return;
    }
    
    print "  Found $test_count tests\n";
    
    # Add TAP plan to the beginning of the file
    my $modified_content = add_tap_plan($content, $test_count);
    
    if ($dry_run) {
        print "  DRY RUN: Would add plan '1..$test_count'\n";
    } else {
        # Backup original file
        copy($file, "$file$backup_suffix") or die "Cannot backup $file: $!";
        
        # Write modified content
        write_file($file, $modified_content);
        print "  MODIFIED: Added plan '1..$test_count' (backup: $file$backup_suffix)\n";
        
        # Verify the modification worked
        if (verify_test_file($file)) {
            print "  VERIFIED: Test file passes TAP validation\n";
        } else {
            print "  WARNING: Test file may have issues after modification\n";
        }
    }
}

sub count_tests_by_execution {
    my $file = shift;
    
    # Try to run the test and count TAP output
    my $output = `perl "$file" 2>/dev/null`;
    my $exit_code = $? >> 8;
    
    # Count lines that look like TAP test results
    my @test_lines = $output =~ /^((?:not )?ok(?:\s+\d+)?.*?)$/gm;
    
    # Alternative: try with prove if direct execution didn't work well
    if (@test_lines == 0 && $exit_code == 0) {
        # Try a different approach - count print/say statements that output test results
        my $content = read_file($file);
        @test_lines = $content =~ /(?:print|say)\s*(?:\()?(?:["'])?(?:not )?ok(?:\s+\d+)?/gi;
    }
    
    return scalar(@test_lines);
}

sub add_tap_plan {
    my ($content, $test_count) = @_;
    
    # Find the best place to insert the plan
    # Look for shebang, use statements, or just put it at the top
    
    my $tests = 'print "1..' . $test_count . '\\n";';

    if ($content =~ /^(#!.*?\n)/) {
        # After shebang
        $content =~ s/^(#!.*?\n)/$1\n$tests\n/;
    } elsif ($content =~ /^(\s*use\s+.*?\n(?:\s*use\s+.*?\n)*)/m) {
        # After use statements
        $content =~ s/^(\s*use\s+.*?\n(?:\s*use\s+.*?\n)*)/$1\n$tests\n/m;
    } else {
        # At the very beginning
        $content = "$tests\n\n" . $content;
    }
    
    return $content;
}

sub verify_test_file {
    my $file = shift;
    
    # Run with prove to verify TAP compliance
    my $prove_output = `prove -v "$file" 2>&1`;
    my $exit_code = $? >> 8;
    
    return $exit_code == 0;
}

__END__

=head1 NAME

tap_test_fixer.pl - Add TAP test counts to old Perl test files

=head1 SYNOPSIS

    perl tap_test_fixer.pl [directory] [options]

=head1 OPTIONS

    --dry-run    Show what would be done without making changes
    --verbose    Show detailed processing information

=head1 DESCRIPTION

This script scans for old Perl test files that don't use Test::More and
manually print "ok"/"not ok" statements. It counts the number of tests
by executing the files and adds the appropriate TAP plan (1..N) to make
them compliant with TAP (Test Anything Protocol).

Files are backed up with a .bak extension before modification.

=head1 EXAMPLES

    # Dry run to see what would be changed
    perl tap_test_fixer.pl . --dry-run --verbose
    
    # Actually fix the tests
    perl tap_test_fixer.pl ./t
    
    # Fix tests in current directory with verbose output
    perl tap_test_fixer.pl . --verbose

=cut
