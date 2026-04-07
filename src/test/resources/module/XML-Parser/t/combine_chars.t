use strict;
use warnings;
use Test::More tests => 3;
use XML::Parser;
use File::Temp qw(tempfile);

# Test 1: module loads
ok( 1, 'XML::Parser loaded' );

# Test that character data spanning buffer boundaries is correctly delivered
# across multiple Char handler calls (GitHub issue #56 / rt.cpan.org #122970).
#
# The expat parser uses a fixed-size read buffer (32 KiB). When character
# data straddles two buffer fills, the Char handler is invoked once for each
# chunk. This is documented, correct behaviour — user code must concatenate
# successive Char calls between Start/End events.

my $bufsize   = 32768;              # must match BUFSIZE in Expat.xs
my $text_len  = $bufsize + 512;     # guaranteed to cross at least one boundary
my $long_text = 'A' x $text_len;
my $doc       = "<r>$long_text</r>";

# Write to a temp file — string parsing hands expat the whole buffer at once,
# so the split only occurs when parsing from a stream/file.
my ( $fh, $tmpfile ) = tempfile( UNLINK => 1, SUFFIX => '.xml' );
binmode($fh);
print $fh $doc;
close $fh;

# Test 2: multiple Char calls are made for text crossing a boundary
my $char_calls  = 0;
my $accumulated = '';

my $p = XML::Parser->new(
    Handlers => {
        Char => sub {
            $char_calls++;
            $accumulated .= $_[1];
        },
    }
);
$p->parsefile($tmpfile);

cmp_ok( $char_calls, '>', 1, 'multiple Char calls for text crossing buffer boundary' );

# Test 3: concatenated chunks equal the original text
is( $accumulated, $long_text, 'accumulated character data matches original' );
