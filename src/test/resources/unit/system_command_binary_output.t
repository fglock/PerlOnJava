use strict;
use warnings;
use Test::More tests => 1;

my $command = qq{"$^X" -e "binmode STDOUT; print STDOUT pack q(H*), q(82a0ff)"};
my $output = qx{$command};

is(unpack('H*', $output), '82a0ff', 'qx preserves arbitrary subprocess bytes');
