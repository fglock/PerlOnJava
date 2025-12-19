use strict;
use warnings;

my $test_logging = 1;
print "Test logging variable: $test_logging\n";

use Test2::API;

print "Loaded Test2::API\n";

package main;

# Check the array directly
print "Perl check: \@Test2::API::EXPORT_OK has " . scalar(@Test2::API::EXPORT_OK) . " elements\n";
print "Perl check: \@Test2::API::EXPORT_OK ref: " . \@Test2::API::EXPORT_OK . "\n";
print "Content: " . join(", ", @Test2::API::EXPORT_OK) . "\n";

Test2::API->import();
