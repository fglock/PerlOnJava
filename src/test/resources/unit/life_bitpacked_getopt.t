#!/usr/bin/perl
# Test for Getopt::Long regression - demonstrates the exact life_bitpacked.pl issue
#
# This test should pass in working commit 6d7f5563
# This test should fail in problematic commit d6400c01d871ffb
#
# The failure manifests as:
# - ASM frame computation errors in Getopt::Long processing
# - "Unknown option" errors for valid command line options

use 5.32.0;
use strict;
use warnings;
use Test::More tests => 1;

# Simulate the exact usage pattern from life_bitpacked.pl that fails
my $width = 64;
my $height = 32;
my $generations = 100;
my $resolution = "auto";
my $pattern = "auto";
my $help = 0;

# Save original @ARGV
my @original_argv = @ARGV;

# Set @ARGV to simulate command line arguments that fail in the regression
@ARGV = ('--width=10', '--height=5', '--generations=1');

eval {
    use Getopt::Long;
    GetOptions(
        'width|w=i'        => \$width,
        'height|h=i'       => \$height,
        'generations|g=i'  => \$generations,
        'resolution|r=s'   => \$resolution,
        'pattern|p=s'      => \$pattern,
        'help|?'           => \$help,
    ) or die("Error in command line arguments\n");
};

# Restore original @ARGV
@ARGV = @original_argv;

if ($@) {
    # In the broken commit, this will fail with "Unknown option" errors
    fail("Getopt::Long parsing failed: $@");
} else {
    # In the working commit, this should succeed
    pass("Getopt::Long parsing succeeded");
}
