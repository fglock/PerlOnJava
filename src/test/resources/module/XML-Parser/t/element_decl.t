use strict;
use warnings;
use Test::More tests => 11;
use XML::Parser;

# Test the Element declaration handler and generate_model() XS function.
# PR #184/#186 added explicit switch cases for XML_CTYPE_EMPTY and
# XML_CTYPE_ANY in generate_model().

my %models;

my $parser = XML::Parser->new(
    Handlers => {
        Element => sub {
            my ($expat, $name, $model) = @_;
            $models{$name} = $model;
        },
    },
);

my $xml = <<'XML';
<?xml version="1.0"?>
<!DOCTYPE doc [
  <!ELEMENT doc (alpha|beta)*>
  <!ELEMENT alpha EMPTY>
  <!ELEMENT beta ANY>
  <!ELEMENT gamma (#PCDATA)>
  <!ELEMENT delta (alpha, beta)>
]>
<doc/>
XML

$parser->parse($xml);

# EMPTY element
ok( exists $models{alpha}, 'Element handler called for EMPTY declaration' );
ok( $models{alpha}->isempty, 'EMPTY model isempty() returns true' );
is( "$models{alpha}", 'EMPTY', 'EMPTY model stringifies to EMPTY' );

# ANY element
ok( exists $models{beta}, 'Element handler called for ANY declaration' );
ok( $models{beta}->isany, 'ANY model isany() returns true' );
is( "$models{beta}", 'ANY', 'ANY model stringifies to ANY' );

# MIXED content (#PCDATA)
ok( exists $models{gamma}, 'Element handler called for MIXED declaration' );
ok( $models{gamma}->ismixed, 'MIXED model ismixed() returns true' );

# SEQ content
ok( exists $models{delta}, 'Element handler called for SEQ declaration' );
ok( $models{delta}->isseq, 'SEQ model isseq() returns true' );
my @children = $models{delta}->children;
is( scalar @children, 2, 'SEQ model has correct number of children' );
