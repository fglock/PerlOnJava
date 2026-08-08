use strict;
use warnings;
use Test::More tests => 1;

undef @ARGV;
close STDIN;
my $line = <<>>;
ok(!defined($line), 'double diamond returns undef when STDIN is closed');
