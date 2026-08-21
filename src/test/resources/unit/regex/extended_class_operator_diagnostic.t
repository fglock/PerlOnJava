use strict;
use warnings;
use Test::More tests => 1;

no warnings 'experimental::regex_sets';
my $compiled = eval q{ qr/(?[ + \t ])/; 1 };
ok(!$compiled && $@ =~ /Unexpected binary operator '\+' with no preceding operand/,
   'extended-class binary operator without an operand uses the Perl diagnostic');
