use strict;
use warnings;
use Test::More tests => 2;
use XML::Parser;

# Test 1: module loads
ok( 1, 'XML::Parser loaded' );

# Test 2: CDATA section content is correctly captured
my $cdata_part = "<<< & > '' << &&&>&&&&;<";
my $doc        = "<foo> hello <![CDATA[$cdata_part]]> there</foo>";
my $acc        = '';

my $parser = XML::Parser->new(
    ErrorContext => 2,
    Handlers     => {
        CdataStart => sub { $_[0]->setHandlers( Char => sub { $acc .= $_[1] } ) },
        CdataEnd   => sub { $_[0]->setHandlers( Char => 0 ) },
    }
);

$parser->parse($doc);

is( $acc, $cdata_part, 'CDATA section content captured correctly' );
