use strict;
use warnings;
use Test::More;

BEGIN {
    eval { require XML::LibXML; XML::LibXML->import; 1 }
        or plan skip_all => 'XML::LibXML is not installed';
}

plan tests => 2;

my $document = XML::LibXML->load_xml(
    string => '<root xmlns="urn:example" first="one" second="two"/>',
);
my @attributes = $document->documentElement->getAttributes;

is(scalar @attributes, 2, 'getAttributes returns every attribute');
is_deeply(
    { map { $_->getName => $_->getValue } @attributes },
    { first => 'one', second => 'two' },
    'getAttributes returns attribute nodes',
);
