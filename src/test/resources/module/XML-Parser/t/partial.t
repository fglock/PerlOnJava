use strict;
use warnings;
use Test::More tests => 3;
use XML::Parser;

# Test 1: module loads
ok( 1, 'XML::Parser loaded' );

my $cnt = 0;
my $str;

sub tmpchar {
    my ( $xp, $data ) = @_;

    if ( $xp->current_element eq 'day' ) {
        $str = $xp->original_string;
        $xp->setHandlers( Char => 0 );
    }
}

my $p = XML::Parser->new(
    Handlers => {
        Comment => sub { $cnt++ },
        Char    => \&tmpchar,
    }
);

my $xpnb = $p->parse_start;

open( my $rec, '<', 'samples/REC-xml-19980210.xml' );

while (<$rec>) {
    $xpnb->parse_more($_);
}

close($rec);

$xpnb->parse_done;

is( $cnt, 37, 'parse_start/parse_more counted 37 comments' );

# original_string relies on XML_GetInputContext which returns NULL
# when libexpat is compiled without XML_CONTEXT_BYTES (e.g. DragonFlyBSD).
SKIP: {
    skip 'original_string not available (expat compiled without XML_CONTEXT_BYTES)', 1
        if !defined $str || $str eq '';
    is( $str, '&draft.day;', 'original_string returns unexpanded entity ref' );
}
