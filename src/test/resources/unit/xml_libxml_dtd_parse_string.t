use strict;
use warnings;
use Test::More;

unless (eval { require XML::LibXML; 1 }) {
    plan skip_all => 'XML::LibXML is unavailable under this Perl';
}

my $dtd_text = '<!ELEMENT root (item*)><!ELEMENT item (#PCDATA)>';
my $dtd = eval { XML::LibXML::Dtd->parse_string($dtd_text) };
ok($dtd, 'parse_string returns a DTD object') or diag $@;
isa_ok($dtd, 'XML::LibXML::Dtd') if $dtd;
is($dtd->nodeType, XML::LibXML::XML_DTD_NODE(), 'result is a document type node')
    if $dtd;
like($dtd->toString, qr/<!ELEMENT root \(item\*\)>/,
    'parsed DTD retains its element declarations') if $dtd;

my $parameter_dtd = eval {
    XML::LibXML::Dtd->parse_string(
        '<!ENTITY % attr_type "CDATA"><!ELEMENT root EMPTY>'
        . '<!ATTLIST root data %attr_type; #IMPLIED>')
};
ok($parameter_dtd,
    'parse_string accepts internal parameter entities in declarations') or diag $@;

done_testing;
