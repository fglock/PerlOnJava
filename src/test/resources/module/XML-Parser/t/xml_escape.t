use strict;
use warnings;
use Test::More tests => 5;
use XML::Parser;

# Test 1: module loads
ok( 1, 'XML::Parser loaded' );

# Capture an expat object from a parse
my $xp_saved;
my $parser = XML::Parser->new( Handlers => { Start => sub { $xp_saved = $_[0] } } );
$parser->parse('<doc/>');

# Test 2: basic escaping of & and <
is( $xp_saved->xml_escape('a & b < c'),
    'a &amp; b &lt; c',
    'xml_escape handles & and <' );

# Test 3: multiple double quotes
is( $xp_saved->xml_escape('say "hello" and "world"', '"'),
    'say &quot;hello&quot; and &quot;world&quot;',
    'xml_escape escapes double quotes' );

# Test 4: multiple single quotes
is( $xp_saved->xml_escape("it's Bob's", "'"),
    "it&apos;s Bob&apos;s",
    'xml_escape escapes single quotes' );

# Test 5: both quote types together
is( $xp_saved->xml_escape(q{He said "it's"}, '"', "'"),
    'He said &quot;it&apos;s&quot;',
    'xml_escape escapes both quote types' );
