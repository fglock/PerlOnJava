use strict;
use warnings;

use Test::More tests => 2;
use XML::LibXML;

my $missing = 'perlonjava-definitely-missing.dtd';
unlink $missing if -e $missing;

my $xml = qq{<!DOCTYPE root SYSTEM "$missing"><root/>};
my $doc = eval { XML::LibXML->load_xml(string => $xml) };

ok($doc, 'parse_string does not load an external DTD by default')
    or diag $@;
is($doc && $doc->documentElement->nodeName, 'root',
    'document remains available without the external DTD');
