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

done_testing;
