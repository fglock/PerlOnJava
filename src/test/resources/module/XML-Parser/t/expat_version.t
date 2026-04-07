#!/usr/bin/perl

use strict;
use warnings;

use Test::More tests => 6;
use XML::Parser::Expat;

# Test expat_version() returns a version string
my $ver = XML::Parser::Expat::expat_version();
ok( defined $ver, "expat_version returns a defined value" );
like( $ver, qr/^expat_\d+\.\d+\.\d+$/, "expat_version format: expat_X.Y.Z" );

# Test expat_version_info() returns version components
my %info = XML::Parser::Expat::expat_version_info();
ok( exists $info{major}, "expat_version_info has 'major' key" );
ok( exists $info{minor}, "expat_version_info has 'minor' key" );
ok( exists $info{micro}, "expat_version_info has 'micro' key" );

# Verify consistency between the two functions
my $expected = "expat_$info{major}.$info{minor}.$info{micro}";
is( $ver, $expected, "expat_version matches expat_version_info components" );
