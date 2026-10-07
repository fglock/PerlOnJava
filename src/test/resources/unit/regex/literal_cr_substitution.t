use strict;
use warnings;
use Test::More tests => 2;

my $text = "before\nmiddle\r\nend\n";
$text =~ s///g;
is($text, "before\nmiddle\nend\n", 'literal carriage return removal preserves line feeds');
is(($text =~ tr/\n//), 3, 'all three line feeds survive');
