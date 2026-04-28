use strict;
use warnings;

use YAML;
use Test::More tests => 1;
use Test::Deep;

my %in = ( '=' => 'value' );
my $yaml = Dump \%in;
my $roundtrip = Load $yaml;
cmp_deeply($roundtrip, \%in, "Roundtrip with '=' hash key");


done_testing;
