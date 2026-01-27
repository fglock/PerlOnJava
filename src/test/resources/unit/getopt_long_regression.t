#!/usr/bin/perl
# Test for Getopt::Long regression - demonstrates ASM frame computation error
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
use Test::More tests => 6;

# Test Getopt::Long functionality that was broken by the regression
BEGIN {
    use_ok('Getopt::Long');
}

# Test 1: Basic Getopt::Long import
can_ok('main', 'GetOptions');

# Test 2: Simple option parsing without arguments
my $simple_flag = 0;
# Save original @ARGV
my @original_argv = @ARGV;
# Set @ARGV to simulate command line arguments
@ARGV = ('--simple');
ok(GetOptions('simple' => \$simple_flag), 'Simple flag parsing works');
is($simple_flag, 1, 'Simple flag was set correctly');
# Restore original @ARGV
@ARGV = @original_argv;

# Test 3: Option with value
my $with_value = '';
# Save original @ARGV
my @original_argv3 = @ARGV;
@ARGV = ('--value=test123');
ok(GetOptions('value=s' => \$with_value), 'Option with value parsing works');
is($with_value, 'test123', 'Value option was set correctly');
# Restore original @ARGV
@ARGV = @original_argv3;

# Test 4: Multiple options
my $width = 10;
my $height = 5;
my $generations = 1;
# Save original @ARGV
my @original_argv4 = @ARGV;
@ARGV = ('--width=10', '--height=5', '--generations=1');
ok(GetOptions(
    'width=i'      => \$width,
    'height=i'     => \$height, 
    'generations=i'=> \$generations,
), 'Multiple options parsing works');

is_deeply([$width, $height, $generations], [10, 5, 1], 
    'Multiple options were set correctly');
# Restore original @ARGV
@ARGV = @original_argv4;
