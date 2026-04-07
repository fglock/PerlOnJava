use strict;
use warnings;
use Test::More tests => 6;
use XML::Parser;

# Test that xpcroak() properly propagates errors through parse()
# See https://github.com/cpan-authors/XML-Parser/issues/50

# Handler packages must be compiled before use
BEGIN {
    package XpCroakStart;

    our $HANDLER_CALLED = 0;
    our $AFTER_CROAK    = 0;

    sub foo {
        my $expat = shift;
        $HANDLER_CALLED = 1;
        $expat->xpcroak("I croaketh.");
        $AFTER_CROAK = 1;
    }

    package XpCroakEnd;

    our $HANDLER_CALLED = 0;

    sub foo_ {
        my $expat = shift;
        $HANDLER_CALLED = 1;
        $expat->xpcroak("End croak.");
    }
}

# Test 1-3: xpcroak in Subs style Start handler
{
    my $xml    = '<foo id="me">Hello World</foo>';
    my $parser = XML::Parser->new( Style => 'Subs', Pkg => 'XpCroakStart' );
    my $died   = 0;
    eval { $parser->parse($xml); };
    $died = 1 if $@;
    is( $died, 1, "xpcroak in Subs Start handler should die" );
    is( $XpCroakStart::HANDLER_CALLED, 1, "Start handler was called" );
    is( $XpCroakStart::AFTER_CROAK, 0, "code after xpcroak should not execute" );
}

# Test 4-5: xpcroak in Subs style End handler
{
    my $xml    = '<foo>Hello</foo>';
    my $parser = XML::Parser->new( Style => 'Subs', Pkg => 'XpCroakEnd' );
    my $died   = 0;
    eval { $parser->parse($xml); };
    $died = 1 if $@;
    is( $died, 1, "xpcroak in Subs End handler should die" );
    is( $XpCroakEnd::HANDLER_CALLED, 1, "End handler was called" );
}

# Test 6: Subs style still works when handler sub doesn't exist
{
    my $xml    = '<bar>Hello</bar>';
    my $parser = XML::Parser->new( Style => 'Subs', Pkg => 'XpCroakStart' );
    my $died   = 0;
    eval { $parser->parse($xml); };
    $died = 1 if $@;
    is( $died, 0, "missing handler sub should not cause an error" );
}
