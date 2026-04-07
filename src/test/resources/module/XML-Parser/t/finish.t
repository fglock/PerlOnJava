use strict;
use warnings;
use Test::More tests => 3;
use XML::Parser;

# Test 1: module loads
ok( 1, 'XML::Parser loaded' );

my $stcount = 0;
my $encount = 0;

my $parser = XML::Parser->new(
    Handlers => {
        Start => sub {
            my ( $exp, $el ) = @_;
            $stcount++;
            $exp->finish if $el eq 'loc';
        },
        End => sub { $encount++ },
    },
    ErrorContext => 2
);

$parser->parsefile('samples/REC-xml-19980210.xml');

is( $stcount, 12, 'finish() stops after 12 start tags' );
is( $encount, 8,  'finish() delivers 8 end tags' );
