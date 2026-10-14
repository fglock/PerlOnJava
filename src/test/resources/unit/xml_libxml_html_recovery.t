use strict;
use warnings;
use Test::More;

unless (eval { require XML::LibXML; 1 }) {
    plan skip_all => 'XML::LibXML is unavailable under this Perl';
}

my $parser = XML::LibXML->new(recover => 1);
my $doc = $parser->parse_html_string('<div><p>first<p>second &amp; third <</div>');
my @paragraphs = $doc->findnodes('//p');

is(scalar @paragraphs, 2, 'HTML parsing recovers omitted paragraph end tags');
is($paragraphs[0]->textContent, 'first', 'first paragraph text is preserved');
is($paragraphs[1]->textContent, 'second & third <',
    'entity text and an invalid HTML token survive recovery');

my $uppercase_doc = $parser->parse_html_string('<HTML><BODY><A href="/">link</A></BODY></HTML>');
my @lowercase_matches = $uppercase_doc->findnodes('//a');
is(scalar @lowercase_matches, 1,
    'HTML XPath matches source elements regardless of source tag-name case');

done_testing;
