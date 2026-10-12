use strict;
use warnings;
use Test::More;

my $argv_entries = scalar keys %ARGV;
is $argv_entries, 0, '%ARGV is available under strict vars';

done_testing;
