use strict;
use warnings;
use Test::More tests => 15;

use XML::Parser;

################################################################
# Check namespaces

my $docstring = <<'End_of_doc;';
<foo xmlns="urn:blazing-saddles"
     xmlns:bar="urn:young-frankenstein"
     bar:alpha="17">
 <zebra xyz="nothing"/>
 <tango xmlns=""
	xmlns:zoo="urn:high-anxiety"
        beta="blue"
        zoo:beta="green"
        bar:beta="red">
   <?nscheck?>
   <zoo:here/>
   <there/>
 </tango>
 <everywhere/>
</foo>
End_of_doc;

my $gname;
my @results;

sub init {
    my $xp = shift;
    $gname = $xp->generate_ns_name( 'alpha', 'urn:young-frankenstein' );
}

sub start {
    my $xp = shift;
    my $el = shift;

    if ( $el eq 'foo' ) {
        push @results, [ 'foo_ns',      $xp->namespace($el) eq 'urn:blazing-saddles' ];
        push @results, [ 'foo_new_pfx', $xp->new_ns_prefixes == 2 ];

        while (@_) {
            my $att = shift;
            my $val = shift;
            if ( $att eq 'alpha' ) {
                push @results, [ 'foo_eq_name', $xp->eq_name( $gname, $att ) ];
                last;
            }
        }
    }
    elsif ( $el eq 'zebra' ) {
        push @results, [ 'zebra_new_pfx', $xp->new_ns_prefixes == 0 ];
        push @results, [ 'zebra_ns',      $xp->namespace($el) eq 'urn:blazing-saddles' ];
    }
    elsif ( $el eq 'tango' ) {
        push @results, [ 'tango_no_ns',       !$xp->namespace( $_[0] ) ];
        push @results, [ 'tango_beta_same',   $_[0] eq $_[2] ];
        push @results, [ 'tango_beta_neq',    !$xp->eq_name( $_[0], $_[2] ) ];

        my $cnt = 0;
        foreach ( $xp->new_ns_prefixes ) {
            $cnt++ if $_ eq '#default';
            $cnt++ if $_ eq 'zoo';
        }
        push @results, [ 'tango_new_pfx', $cnt == 2 ];
    }
}

sub end {
    my $xp = shift;
    my $el = shift;

    if ( $el eq 'zebra' ) {
        push @results,
          [ 'zebra_expand', $xp->expand_ns_prefix('#default') eq 'urn:blazing-saddles' ];
    }
    elsif ( $el eq 'everywhere' ) {
        push @results, [ 'everywhere_ns', $xp->namespace($el) eq 'urn:blazing-saddles' ];
    }
}

sub proc {
    my $xp     = shift;
    my $target = shift;

    if ( $target eq 'nscheck' ) {
        push @results, [ 'nscheck_no_new', $xp->new_ns_prefixes == 0 ];

        my $cnt = 0;
        foreach ( $xp->current_ns_prefixes ) {
            $cnt++ if $_ eq 'zoo';
            $cnt++ if $_ eq 'bar';
        }
        push @results, [ 'nscheck_cur_pfx', $cnt == 2 ];
        push @results,
          [ 'nscheck_bar', $xp->expand_ns_prefix('bar') eq 'urn:young-frankenstein' ];
        push @results,
          [ 'nscheck_zoo', $xp->expand_ns_prefix('zoo') eq 'urn:high-anxiety' ];
    }
}

my $parser = XML::Parser->new(
    ErrorContext => 2,
    Namespaces   => 1,
    Handlers     => {
        Start => \&start,
        End   => \&end,
        Proc  => \&proc,
        Init  => \&init
    }
);

$parser->parse($docstring);

# Now verify all results collected during parsing
ok( $results[0][1],  'foo element in urn:blazing-saddles namespace' );
ok( $results[1][1],  'foo element introduces 2 new namespace prefixes' );
ok( $results[2][1],  'generated ns name matches alpha attribute' );
ok( $results[3][1],  'zebra introduces no new namespace prefixes' );
ok( $results[4][1],  'zebra inherits urn:blazing-saddles namespace' );
ok( $results[5][1],  'tango beta attribute has no namespace' );
ok( $results[6][1],  'tango beta and zoo:beta have same local name' );
ok( $results[7][1],  'tango beta and zoo:beta are not namespace-equal' );
ok( $results[8][1],  'tango introduces 2 new prefixes (#default, zoo)' );
ok( $results[9][1],  'expand #default after zebra gives urn:blazing-saddles' );
ok( $results[10][1], 'no new namespace prefixes at nscheck PI' );
ok( $results[11][1], 'current prefixes include zoo and bar' );
ok( $results[12][1], 'bar expands to urn:young-frankenstein' );
ok( $results[13][1], 'zoo expands to urn:high-anxiety' );
ok( $results[14][1], 'everywhere element in urn:blazing-saddles namespace' );
