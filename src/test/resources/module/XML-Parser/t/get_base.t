use strict;
use warnings;
use Test::More tests => 5;
use XML::Parser;

# Test the XS GetBase()/SetBase() functions (PPCODE return path).
# PR #184 converted GetBase from CODE/SV* to PPCODE/void with XPUSHs.

my $base_in_handler;

my $parser = XML::Parser->new(
    Handlers => {
        Start => sub {
            my ($expat) = @_;
            $base_in_handler = $expat->base;
        },
    },
);

# 1. base() returns undef when no base has been set
my $expat = $parser->parse('<root/>');
is( $base_in_handler, undef, 'base() returns undef when no base is set' );

# 2-3. base() returns the value after SetBase
$base_in_handler = 'sentinel';
$parser = XML::Parser->new(
    Base     => 'http://example.com/',
    Handlers => {
        Start => sub {
            my ($expat) = @_;
            $base_in_handler = $expat->base;
        },
    },
);
$parser->parse('<root/>');
is( $base_in_handler, 'http://example.com/',
    'base() returns the Base value set at construction' );

# 4. base() can be changed inside a handler
my $new_base;
$parser = XML::Parser->new(
    Base     => '/original',
    Handlers => {
        Start => sub {
            my ($expat, $el) = @_;
            if ($el eq 'root') {
                $expat->base('/changed');
            }
            elsif ($el eq 'child') {
                $new_base = $expat->base;
            }
        },
    },
);
$parser->parse('<root><child/></root>');
is( $new_base, '/changed', 'base() reflects value set inside handler' );

# 5-6. Round-trip: set and get various base values
my @bases;
$parser = XML::Parser->new(
    Handlers => {
        Start => sub {
            my ($expat, $el) = @_;
            if ($el eq 'a') {
                $expat->base('file:///tmp/test.xml');
            }
            elsif ($el eq 'b') {
                push @bases, $expat->base;
                $expat->base(undef);
            }
            elsif ($el eq 'c') {
                push @bases, $expat->base;
            }
        },
    },
);
$parser->parse('<a><b/><c/></a>');
is( $bases[0], 'file:///tmp/test.xml', 'base persists across elements' );
is( $bases[1], undef, 'base returns undef after being reset to undef' );
