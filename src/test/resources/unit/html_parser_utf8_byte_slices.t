use strict;
use warnings;
use Encode qw(encode);
use HTML::Entities;
use HTML::Parser;
use Test::More tests => 4;

# HTML::Parser receives file content as octets.  Its text callbacks must retain
# that representation: marking an octet slice as Unicode makes a later /\s/
# see UTF-8 continuation byte C2 A0 as a non-breaking space.
my $thai = join '', map { chr } (
    0x0E04, 0x0E33, 0x0E23, 0x0E49, 0x0E2D, 0x0E07, 0x0E02, 0x0E2D,
    0x0E04, 0x0E37, 0x0E19, 0x0E40, 0x0E07, 0x0E34, 0x0E19, 0x0E20,
    0x0E32, 0x0E29, 0x0E35, 0x0E2D, 0x0E32, 0x0E01, 0x0E23,
);
my $thai_octets = encode('UTF-8', $thai);
my $octets = "Thai: $thai_octets";
my @text;

my $parser = HTML::Parser->new(
    api_version => 3,
    handlers    => { text => [ sub { push @text, $_[0] }, 'dtext' ] },
);
$parser->parse("<p>$octets</p>");
$parser->eof;

is_deeply(\@text, [ $octets ], 'parser text callback preserves UTF-8 octets');

my @parts = split /(\s+)/, $text[0];
is_deeply(\@parts, [ 'Thai:', ' ', $thai_octets ],
    'split does not treat a UTF-8 continuation byte as whitespace');
is(join('', @parts), $octets, 'split round trip preserves the complete Thai suffix');

my $tree_text = $octets;
HTML::Entities::decode($tree_text); # HTML::TreeBuilder decodes every non-CDATA text node this way.
my @tree_parts = split /(\s+)/, $tree_text;
is_deeply(\@tree_parts, [ 'Thai:', ' ', $thai_octets ],
    'a no-op entity decode preserves UTF-8 octets for TreeBuilder');
