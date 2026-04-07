use strict;
use warnings;
use Test::More tests => 29;

use XML::Parser;
use FileHandle;

my $parser = XML::Parser->new( ProtocolEncoding => 'ISO-8859-1' );
ok( $parser, 'parser created with ISO-8859-1 encoding' );

my @ndxstack;
my $indexok = 1;

# Need these external entities

open( ZOE, '>zoe.ent' ) or die "Cannot write zoe.ent: $!";
print ZOE "'cute'";
close(ZOE);

open( PAUL, '>paul.ent' ) or die "Cannot write paul.ent: $!";
print PAUL "'Paul'";
close(PAUL);

open( PAULA, '>paula.ent' ) or die "Cannot write paula.ent: $!";
print PAULA "'Paula'";
close(PAULA);

# XML string for tests

my $xmlstring = <<"End_of_XML;";
<!DOCTYPE foo
  [
    <!NOTATION bar PUBLIC "qrs">
    <!ENTITY zinger PUBLIC "xyz" "abc" NDATA bar>
    <!ENTITY fran SYSTEM "fran-def">
    <!ENTITY zoe  SYSTEM "zoe.ent">
    <!ENTITY paul  SYSTEM "paul.ent">
    <!ENTITY paula SYSTEM "paula.ent">
   ]>
<foo>
  First line in foo
  <boom>Fran is &fran; and Zoe is &zoe;</boom>
  <boom2>&paul; &amp; &paula;</boom2>
  <bar id="jack" stomp="jill">
  <?line-noise *&*&^&<< ?>
    1st line in bar
    <blah> 2nd line in bar </blah>
    3rd line in bar <!-- Isn't this a doozy -->
  </bar>
  <zap ref="zing" />
  This, '\240', would be a bad character in UTF-8.
</foo>
End_of_XML;

# Handlers — collect results into arrays for later verification
my @results;
my $pos = '';

sub ch {
    my ( $p, $str ) = @_;
    $results[0]++;    # char handler called
    $results[1]++ if ( $str =~ /2nd line/ and $p->in_element('blah') );
    if ( $p->in_element('boom') ) {
        $results[13]++ if $str =~ /pretty/;
        $results[14]++ if $str =~ /cute/;
    }
    elsif ( $p->in_element('boom2') ) {
        $results[24]++ if $str =~ /\bPaul\b/;
        $results[25]++ if $str =~ /\bPaula\b/;
    }
}

sub st {
    my ( $p, $el, %atts ) = @_;

    $ndxstack[ $p->depth ] = $p->element_index;
    $results[2]++ if ( $el eq 'bar' and $atts{stomp} eq 'jill' );
    if ( $el eq 'zap' and $atts{'ref'} eq 'zing' ) {
        $results[3]++;
        $p->default_current;
    }
    elsif ( $el eq 'bar' ) {
        $results[18]++ if $p->recognized_string eq '<bar id="jack" stomp="jill">';
    }
}

sub eh {
    my ( $p, $el ) = @_;
    $indexok = 0 unless $p->element_index == $ndxstack[ $p->depth ];
    if ( $el eq 'zap' ) {
        $results[4]++;
        my @old = $p->setHandlers( 'Char', \&newch );
        $results[15]++ if $p->current_line == 20;
        $results[16]++ if $p->current_column == 20;
        $results[19]++ if ( $old[0] eq 'Char' and $old[1] == \&ch );
    }
    if ( $el eq 'boom' ) {
        $p->setHandlers( 'Default', \&dh );
    }
}

sub dh {
    my ( $p, $str ) = @_;
    if ( $str =~ /doozy/ ) {
        $results[5]++;
        $pos = $p->position_in_context(1);
    }
    $results[6]++ if $str =~ /^<zap/;
}

sub pi {
    my ( $p, $tar, $data ) = @_;

    $results[7]++ if ( $tar eq 'line-noise' and $data =~ /&\^&<</ );
}

sub notation_handler {
    my ( $p, $name, $base, $sysid, $pubid ) = @_;

    $results[8]++ if ( $name eq 'bar' and $pubid eq 'qrs' );
}

sub unp {
    my ( $p, $name, $base, $sysid, $pubid, $notation ) = @_;

    $results[9]++ if ( $name eq 'zinger'
        and $pubid eq 'xyz'
        and $sysid eq 'abc'
        and $notation eq 'bar' );
}

sub newch {
    my ( $p, $str ) = @_;

    if ( $] < 5.007001 ) {
        $results[10]++ if $str =~ /'\302\240'/;
    }
    else {
        $results[10]++ if $str =~ /'\xa0'/;
    }
}

sub extent {
    my ( $p, $base, $sys, $pub ) = @_;

    if ( $sys eq 'fran-def' ) {
        $results[11]++;
        return 'pretty';
    }
    elsif ( $sys eq 'zoe.ent' ) {
        $results[12]++;

        open( FOO, '<', $sys ) or die "Couldn't open $sys";
        return *FOO;
    }
    elsif ( $sys eq 'paul.ent' ) {
        $results[22]++;

        open( FOO, '<', $sys ) or die "Couldn't open $sys";
        return \*FOO;
    }
    elsif ( $sys eq 'paula.ent' ) {
        $results[23]++;

        open( my $fh, '<', $sys ) or die "Couldn't open $sys";
        return $fh;
    }
}

$parser->setHandlers(
    'Char'         => \&ch,
    'Start'        => \&st,
    'End'          => \&eh,
    'Proc'         => \&pi,
    'Notation'     => \&notation_handler,
    'Unparsed'     => \&unp,
    'ExternEnt'    => \&extent,
    'ExternEntFin' => sub { close(FOO); }
);

eval { $parser->parsestring($xmlstring); };

# Clean up external entity files
unlink('zoe.ent')   if ( -f 'zoe.ent' );
unlink('paul.ent')  if ( -f 'paul.ent' );
unlink('paula.ent') if ( -f 'paula.ent' );

is( $@, '', 'parse completed without error' );

ok( $results[0],  'char handler was called' );
ok( $results[1],  'in_element(blah) detected 2nd line' );
ok( $results[2],  'start handler saw bar with stomp=jill' );
ok( $results[3],  'start handler saw zap with ref=zing, called default_current' );
ok( $results[4],  'end handler saw zap, swapped char handler' );
ok( $results[5],  'default handler saw doozy comment' );
ok( $results[6],  'default handler saw <zap element' );
ok( $results[7],  'proc handler saw line-noise PI' );
ok( $results[8],  'notation handler saw bar with pubid qrs' );
ok( $results[9],  'unparsed handler saw zinger entity' );
ok( $results[10], 'new char handler saw non-UTF8 character' );
ok( $results[11], 'extern ent handler returned string for fran-def' );
ok( $results[12], 'extern ent handler returned bare glob for zoe.ent' );
ok( $results[13], 'fran external entity resolved to pretty' );
ok( $results[14], 'zoe external entity resolved to cute' );
ok( $results[15], 'current_line correct at end of zap' );
ok( $results[16], 'current_column correct at end of zap' );
ok( $results[18], 'recognized_string correct for bar start tag' );
ok( $results[19], 'setHandlers returned old Char handler' );

my $cmpstr = << 'End_of_Cmp;';
    <blah> 2nd line in bar </blah>
    3rd line in bar <!-- Isn't this a doozy -->
===================^
  </bar>
End_of_Cmp;

is( $pos, $cmpstr, 'position_in_context shows correct context' );

ok( $indexok, 'element_index consistent across start/end pairs' );

# Test that memory leak through autovivifying symbol table entries is fixed.

my $count = 0;
$parser = XML::Parser->new(
    Handlers => {
        Start => sub { $count++ }
    }
);

$xmlstring = '<a><b>Sea</b></a>';

eval { $parser->parsestring($xmlstring); };

is( $count, 2, 'parsed 2 start tags in simple XML' );

ok( !exists $::{$xmlstring}, 'XML string did not auto-vivify symbol table entry' );

ok( $results[22], 'extern ent handler returned glob ref for paul.ent' );
ok( $results[23], 'extern ent handler returned lexical fh for paula.ent' );
ok( $results[24], 'boom2 char data includes Paul' );
ok( $results[25], 'boom2 char data includes Paula' );
