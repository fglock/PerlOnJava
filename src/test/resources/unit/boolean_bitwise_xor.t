use strict;
use warnings;
use Test::More tests => 4;

my $true = !!1;
my $false = !!0;

is($true ^ $true, 0, 'boolean XOR true/true');
is($false ^ $true, 1, 'boolean XOR false/true');
is($true ^ $false, 1, 'boolean XOR true/false');
is($false ^ $false, 0, 'boolean XOR false/false');
