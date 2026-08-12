use strict;
use warnings;
use Test::More;
use XML::LibXML;
use XML::LibXSLT;

my $style = XML::LibXML->load_xml(string => <<'XSL');
<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
  <xsl:param name="greeting"/>
  <xsl:output method="text"/>
  <xsl:template match="/"><xsl:value-of select="$greeting"/><xsl:value-of select="doc/item"/></xsl:template>
</xsl:stylesheet>
XSL
my $document = XML::LibXML->load_xml(string => '<doc><item>world</item></doc>');
my $stylesheet = XML::LibXSLT->new->parse_stylesheet($style);

ok($stylesheet->can('transform'), 'parsed stylesheet exposes the transformation API');
is($stylesheet->output_method, 'text', 'reads the declared output method');
is(
    $stylesheet->output_as_chars(
        $stylesheet->transform($document, greeting => q{'hello '}),
    ),
    'hello world',
    'transforms XML and passes quoted XPath string parameters',
);

done_testing;
