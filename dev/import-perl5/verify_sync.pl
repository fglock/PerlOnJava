#!/usr/bin/env perl
use strict;
use warnings;
use File::Basename qw(dirname);
use File::Spec;
use Cwd qw(abs_path);

=head1 NAME

verify_sync.pl - Verify that modules in sync config actually exist

=head1 DESCRIPTION

Checks that all modules and test directories configured in config.yaml
actually exist and are present in the expected locations.

=cut

my $script_dir = dirname(abs_path($0));
my $project_root = abs_path(File::Spec->catdir($script_dir, '..', '..'));
my $config_file = File::Spec->catfile($script_dir, 'config.yaml');

print "Verifying sync configuration...\n";
print "Project root: $project_root\n\n";

chdir $project_root or die "Cannot chdir to $project_root: $!\n";

# Parse config
open my $fh, '<', $config_file or die "Cannot open $config_file: $!\n";

my ($total, $missing_src, $present_target, $missing_target) = (0, 0, 0, 0);
my @issues;

while (my $line = <$fh>) {
    next unless $line =~ /^\s*-?\s*source:\s+(.+)/;
    my $source = $1;
    $source =~ s/^\s+|\s+$//g;
    $total++;
    
    # Get target
    my $target_line = <$fh>;
    my ($target) = $target_line =~ /target:\s+(.+)/;
    $target =~ s/^\s+|\s+$//g if $target;
    
    # Check source exists
    unless (-e $source) {
        $missing_src++;
        push @issues, "Missing source: $source";
    }
    
    # Check if target exists (after sync)
    if ($target) {
        if (-e $target) {
            $present_target++;
        } else {
            $missing_target++;
        }
    }
}
close $fh;

print "=" x 60 . "\n";
print "Verification Results:\n";
print "=" x 60 . "\n";
print "Total imports configured: $total\n";
print "Source files exist: " . ($total - $missing_src) . "\n";
print "Source files missing: $missing_src\n";
print "Target files/dirs present: $present_target\n";
print "Target files/dirs missing: $missing_target\n";
print "\n";

if (@issues) {
    print "Issues found:\n";
    for my $issue (@issues) {
        print "  - $issue\n";
    }
    print "\n";
}

if ($missing_src > 0) {
    print "⚠️  Some source files are missing. This is a problem.\n";
    exit 1;
}

if ($missing_target > 0) {
    print "ℹ️  Some target files are missing. Run: perl dev/import-perl5/sync.pl\n";
} else {
    print "✓ All configured files are synced correctly!\n";
}

print "\nTest directories in perl5_t/:\n";
my @test_dirs = `find perl5_t -maxdepth 1 -type d | sort`;
chomp @test_dirs;
print "  " . scalar(@test_dirs) . " directories\n";

my $test_count = `find perl5_t -name '*.t' -type f | wc -l`;
chomp $test_count;
$test_count =~ s/^\s+//;
print "  $test_count test files\n";

