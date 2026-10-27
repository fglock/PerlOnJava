use strict;
use warnings;
use Test::More;
use XML::LibXML;
use XML::LibXSLT;

my $style = XML::LibXML->load_xml(string => <<'XSL');
<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
  <xsl:param name="prefix"/>
  <xsl:output method="text"/>
  <xsl:template match="/"> <xsl:value-of select="$prefix"/><xsl:value-of select="root/name"/> </xsl:template>
</xsl:stylesheet>
XSL
my $input = XML::LibXML->load_xml(string => '<root><name>PerlOnJava</name></root>');
my $sheet = XML::LibXSLT->new->parse_stylesheet($style);
ok($sheet->can('transform'), 'parsed stylesheet exposes the transformation API');
is($sheet->output_method, 'text', 'reports the stylesheet output method');
my $result = $sheet->transform($input, prefix => q{'Hello '});
is($sheet->output_as_chars($result), 'Hello PerlOnJava', 'transforms DOM input with a parameter');

my $xml_style = XML::LibXML->load_xml(string => <<'XSL');
<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
  <xsl:output method="xml" omit-xml-declaration="yes"/>
  <xsl:template match="/">
    <result><xsl:apply-templates select="root/item"/></result>
  </xsl:template>
  <xsl:template match="item"><entry><xsl:value-of select="."/></entry></xsl:template>
</xsl:stylesheet>
XSL
my $xml_input = XML::LibXML->load_xml(string => '<root><item>kept</item></root>');
my $xml_sheet = XML::LibXSLT->new->parse_stylesheet($xml_style);
my $xml_result = $xml_sheet->transform($xml_input);
my $xml_output = $xml_sheet->output_as_chars($xml_result);
$xml_output =~ s/\s+\z//;
is(
    $xml_output,
    '<result><entry>kept</entry></result>',
    'serializes transformed XML without applying the stylesheet a second time',
);

done_testing;
