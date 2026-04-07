use strict;
use warnings;
use Test::More tests => 2;
use XML::Parser;

# Test that xpcarp() interpolates $line (not literal '$line')
{
    my $xml    = '<foo>Hello</foo>';
    my $parser = XML::Parser->new(
        Handlers => {
            Start => sub {
                my $expat = shift;
                $expat->xpcarp("test warning");
            },
        },
    );

    my $warning = '';
    local $SIG{__WARN__} = sub { $warning = $_[0] };
    $parser->parse($xml);

    like( $warning, qr/at line \d+/,
        "xpcarp interpolates \$line variable (not literal '\$line')" );
    unlike( $warning, qr/\$line/,
        "xpcarp warning does not contain literal '\$line'" );
}
