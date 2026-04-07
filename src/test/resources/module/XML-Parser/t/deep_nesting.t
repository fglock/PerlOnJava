use strict;
use warnings;
use Test::More tests => 1;

# Test for deeply nested elements to exercise st_serial_stack reallocation.
# The stack is initially allocated at 1024 entries and grows by 512.
# Use depth > 1024 to actually trigger reallocation (GH #39, GH #215).

use XML::Parser;

my $depth = 2048;

my $xml = '';
for my $i ( 1 .. $depth ) {
    $xml .= "<e$i>";
}
for my $i ( reverse 1 .. $depth ) {
    $xml .= "</e$i>";
}

my $p = XML::Parser->new;
eval { $p->parse($xml) };

is( $@, '', "parse $depth deeply nested elements without error" );
