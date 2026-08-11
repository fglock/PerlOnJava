use strict;
use warnings;
use Test::More;
use File::Temp qw(tempfile);
use XML::LibXML::Reader;

my $xml = '<root><record id="1"><value>one</value></record><record id="2"/></root>';

my $string_reader = XML::LibXML::Reader->new(string => $xml);
ok($string_reader->nextElement('record'), 'finds an element by name');
is($string_reader->name, 'record', 'reports the current element name');
my $node = $string_reader->copyCurrentNode(1);
is($node->getAttribute('id'), '1', 'deep copy retains attributes');
is($node->getChildrenByTagName('value')->[0]->textContent, 'one',
    'deep copy retains descendants');
ok($string_reader->nextElement('record'), 'continues after the current element');
is($string_reader->copyCurrentNode(1)->getAttribute('id'), '2',
    'second matching element is returned');
ok(!$string_reader->nextElement('record'), 'returns false at end of document');

my ($fh, $filename) = tempfile();
print {$fh} $xml;
close $fh;
my $file_reader = XML::LibXML::Reader->new(location => $filename);
ok($file_reader->nextElement('value'), 'location constructor parses files');
is($file_reader->copyCurrentNode(1)->textContent, 'one',
    'file reader copies the current node');

open my $input, '<', \$xml or die $!;
my $io_reader = XML::LibXML::Reader->new(IO => $input);
ok($io_reader->nextElement('record'), 'IO constructor parses filehandles');
is($io_reader->copyCurrentNode(0)->getAttribute('id'), '1',
    'shallow copy retains attributes');
ok(!$io_reader->copyCurrentNode(0)->hasChildNodes,
    'shallow copy omits descendants');

my $bom_xml = "\xEF\xBB\xBF<root><record id=\"bom\"/></root>";
my $bom_reader = XML::LibXML::Reader->new(string => $bom_xml);
ok($bom_reader->nextElement('record'), 'UTF-8 BOM byte string is accepted');
is($bom_reader->copyCurrentNode(1)->getAttribute('id'), 'bom',
    'UTF-8 BOM is consumed before parsing');

done_testing;
