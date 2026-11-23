use strict;
use warnings;
use Test::More;

BEGIN {
    eval { require XML::LibXML; XML::LibXML->import; 1 }
        or plan skip_all => 'XML::LibXML is not installed';
}

my $xml = '<root><item name="first" exec="run" modified="today" count="1" id="entry"/></root>';
my $doc = XML::LibXML->load_xml(string => $xml);

is($doc->toString(0) =~ /<item name="first" exec="run" modified="today" count="1" id="entry"\/>/ ? 1 : 0,
    1, 'serialization preserves parsed attribute order');

ok(eval { $doc->indexElements; 1 }, 'indexElements is available');
is($doc->getElementById('entry')->getName, 'item', 'indexed ID can be looked up');

done_testing;
