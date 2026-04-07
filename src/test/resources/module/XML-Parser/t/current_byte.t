use Test::More tests => 4;
use XML::Parser;

# Test that current_byte returns correct non-negative byte positions.
# See https://github.com/cpan-authors/XML-Parser/issues/48
# On 32-bit perls with XML_LARGE_SIZE expat, the old XS code truncated
# XML_Index (long long) to long, causing overflow for files > 2GB.

my @byte_positions;

my $parser = XML::Parser->new(
    Handlers => {
        Start => sub {
            my ($expat, $el) = @_;
            push @byte_positions, $expat->current_byte;
        },
    },
);

# Parse a simple XML string with known byte offsets
my $xml = '<root><child>text</child><child2/></root>';
$parser->parse($xml);

# current_byte should return the byte offset of each start tag
is($byte_positions[0], 0,  'current_byte for root element is 0');
is($byte_positions[1], 6,  'current_byte for child element is 6');
is($byte_positions[2], 25, 'current_byte for child2 element is 25');

# Verify all byte positions are non-negative
my $all_non_negative = 1;
for my $pos (@byte_positions) {
    if (!defined $pos || $pos < 0) {
        $all_non_negative = 0;
        last;
    }
}
ok($all_non_negative, 'all current_byte values are non-negative');
