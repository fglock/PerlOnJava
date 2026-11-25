use strict;
use warnings;
use Test::More;
eval { require XML::LibXML; 1 } or plan skip_all => 'XML::LibXML is unavailable';
plan tests => 5;

my $doc = XML::LibXML->load_xml(string => '<schema/>');
my $root = $doc->documentElement;
my $local_name = eval { $root->localName };
is($@, '', 'localName method resolves');
is($local_name, 'schema', 'localName aliases localname');

my $element = $doc->createElement('Child');
my $text = $element->appendTextNode('hello');
is($element->textContent, 'hello', 'appendTextNode adds element text');
is($text->textContent, 'hello', 'appendTextNode returns the appended text node');
$element->appendTextNode(' world');
is($element->textContent, 'hello world', 'repeated calls append text in order');
