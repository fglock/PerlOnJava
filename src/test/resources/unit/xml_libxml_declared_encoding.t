use strict;
use warnings;
use Test::More;

unless (eval { require XML::LibXML; 1 }) {
    plan skip_all => 'XML::LibXML is unavailable under this Perl';
}

my $parser = XML::LibXML->new;

# The source is a byte string containing UTF-8 octets, while the XML declaration
# says to interpret those octets as ISO-8859-1.  libxml2 honors the declaration
# for parse_string input, so the returned text contains the two byte values
# C3 A9 as separate Latin-1 characters.
my $declared = pack('C*',
    60,63,120,109,108,32,118,101,114,115,105,111,110,61,34,49,46,48,34,
    32,101,110,99,111,100,105,110,103,61,34,73,83,79,45,56,56,53,57,45,
    49,34,63,62,60,110,62,74,111,115,195,169,60,47,110,62);
my $doc = eval { $parser->parse_string($declared) };
ok($doc, 'parse_string accepts a declared encoding on byte input') or diag $@;
is($doc->documentElement->textContent, "Jos\xC3\xA9",
    'declared ISO-8859-1 encoding is applied to input bytes') if $doc;

my $decoded = '<?xml version="1.0" encoding="UTF-8"?><n>'
    . chr(0xE9) . '</n>';
utf8::upgrade($decoded);
my $decoded_doc = eval { $parser->parse_string($decoded) };
ok($decoded_doc, 'parse_string accepts an already-decoded character string')
    or diag $@;
is(ord(substr($decoded_doc->documentElement->textContent, 0, 1)), 0xE9,
    'already-decoded characters remain unchanged') if $decoded_doc;

my $unicode_with_legacy_decl = '<?xml version="1.0" encoding="ISO-8859-1"?><n>Jos'
    . chr(0xE9) . '</n>';
utf8::upgrade($unicode_with_legacy_decl);
my $legacy_doc = eval { $parser->parse_string($unicode_with_legacy_decl) };
ok($legacy_doc, 'parse_string accepts Unicode text with a declared encoding')
    or diag $@;
is(join(',', map { sprintf '%04X', ord($_) }
        split //, $legacy_doc->documentElement->textContent),
    '004A,006F,0073,00C3,00A9',
    'declared encoding is applied to the UTF-8 representation of Perl text')
    if $legacy_doc;

my $bom = pack('C*', 0xEF, 0xBB, 0xBF,
    60,63,120,109,108,32,118,101,114,115,105,111,110,61,34,49,46,48,34,
    32,101,110,99,111,100,105,110,103,61,34,85,84,70,45,56,34,63,62,
    60,110,62,195,169,60,47,110,62);
my $bom_doc = eval { $parser->parse_string($bom) };
ok($bom_doc, 'parse_string accepts a UTF-8 BOM byte string') or diag $@;
is(ord(substr($bom_doc->documentElement->textContent, 0, 1)), 0xE9,
    'UTF-8 BOM input is decoded as UTF-8') if $bom_doc;

done_testing;
