use strict;
use warnings;
use Test::More tests => 4;
use XML::Parser;

# Test 1: module loads
ok( 1, 'XML::Parser loaded' );

my $doc = <<'End_of_Doc;';
<!DOCTYPE foo [
<!ATTLIST bar zz CDATA 'there'>
]>
<foo>
  <bar xx="hello"/>
  <bar zz="other"/>
</foo>
End_of_Doc;

# Track which test numbers to run for each <bar> element
my $test_index = 2;

my $p = XML::Parser->new(
    Handlers => {
        Start => sub {
            my $xp = shift;
            my $el = shift;

            if ( $el eq 'bar' ) {
                my %atts = @_;
                my %isdflt;
                my $specified = $xp->specified_attr;

                for ( my $i = $specified; $i < @_; $i += 2 ) {
                    $isdflt{ $_[$i] } = 1;
                }

                if ( defined $atts{xx} ) {
                    ok( !$isdflt{'xx'}, 'xx attribute is not defaulted' );
                    ok( $isdflt{'zz'},  'zz attribute is defaulted when xx is specified' );
                }
                else {
                    ok( !$isdflt{'zz'}, 'zz attribute is not defaulted when explicitly set' );
                }
            }
        },
    }
);

$p->parse($doc);
