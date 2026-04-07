use strict;
use warnings;
use Test::More tests => 5;
use XML::Parser;

# Test 1: module loads
ok( 1, 'XML::Parser loaded' );

# Test that current_length returns byte counts for events

my $xml = '<root><child attr="val">text</child></root>';

my ( $start_length, $end_length, $char_length );

my $parser = XML::Parser->new(
    Handlers => {
        Start => sub {
            my ( $p, $el ) = @_;
            $start_length = $p->current_length if $el eq 'child';
        },
        End => sub {
            my ( $p, $el ) = @_;
            $end_length = $p->current_length if $el eq 'child';
        },
        Char => sub {
            my ( $p, $str ) = @_;
            $char_length = $p->current_length if $str eq 'text';
        },
    }
);

$parser->parse($xml);

# Test 2: current_length returns a defined positive value for start tags
ok( defined $start_length && $start_length > 0,
    'current_length defined and positive for start tag' );

# Test 3: start tag <child attr="val"> is 18 bytes
is( $start_length, 18, 'start tag <child attr="val"> is 18 bytes' );

# Test 4: end tag </child> is 8 bytes
is( $end_length, 8, 'end tag </child> is 8 bytes' );

# Test 5: character data "text" is 4 bytes
is( $char_length, 4, 'character data "text" is 4 bytes' );
