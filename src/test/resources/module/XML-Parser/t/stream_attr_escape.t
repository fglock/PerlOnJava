use strict;
use warnings;
use Test::More tests => 4;
use XML::Parser;

# GH#101: Stream style Start handler must XML-escape attribute values
# when producing default output (no StartTag handler defined).

my $output = '';

# Capture Stream default output by redirecting STDOUT
sub capture_stream_parse {
    my ($xml) = @_;
    my $parser = XML::Parser->new( Style => 'Stream' );
    my $captured = '';
    open( my $old_stdout, '>&', \*STDOUT ) or die "Can't dup STDOUT: $!";
    close STDOUT;
    open( STDOUT, '>', \$captured ) or die "Can't redirect STDOUT: $!";
    $parser->parse($xml);
    close STDOUT;
    open( STDOUT, '>&', $old_stdout ) or die "Can't restore STDOUT: $!";
    return $captured;
}

# Test 1: Ampersand in attribute value
{
    my $xml = '<root attr="a &amp; b"/>';
    my $out = capture_stream_parse($xml);
    like( $out, qr/&amp;/, 'ampersand in attribute value is escaped' );
    unlike( $out, qr/attr="a & b"/, 'raw ampersand does not appear unescaped' );
}

# Test 2: Quotes in attribute value
{
    my $xml = q{<root attr="he said &quot;hello&quot;"/>};
    my $out = capture_stream_parse($xml);
    like( $out, qr/&quot;/, 'double quotes in attribute value are escaped' );
}

# Test 3: Angle brackets in attribute value
{
    my $xml = '<root attr="a &lt; b"/>';
    my $out = capture_stream_parse($xml);
    like( $out, qr/&lt;/, 'less-than in attribute value is escaped' );
}
