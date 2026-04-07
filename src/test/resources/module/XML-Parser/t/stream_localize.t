use strict;
use warnings;
use Test::More tests => 1;
use XML::Parser;

# GH#72 - Style::Stream should localize $_ so that parsing works
# even when $_ is aliased to a read-only value.

my $xml = '<root><child attr="val">text</child></root>';

# Provide handlers so Stream style doesn't print to STDOUT
# (which confuses the test harness).
{
    package StreamLocalizeTest;
    sub StartTag  { }
    sub EndTag    { }
    sub Text      { }
}
package main;

my $parser = XML::Parser->new( Style => 'Stream', Pkg => 'StreamLocalizeTest' );

my $ok = eval {
    for ("read-only string") {
        # $_ is aliased to a read-only value inside this loop
        $parser->parse($xml);
    }
    1;
};

ok( $ok, 'Style::Stream does not die when $_ is read-only' )
  or diag("Error: $@");
