use strict;
use warnings;
use Test::More tests => 1;

sub caller_line { (caller)[2] }
my $line = eval "\n#line 3000000000\ncaller_line();";
is($line, '3000000000', 'caller preserves a #line value above the JVM int range');
