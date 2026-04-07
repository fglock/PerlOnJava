#!/usr/bin/perl

use strict;
use warnings;

use Test::More;
use XML::Parser;

# Verify expat can handle external DTD processing with parameter entities.
# Some old/buggy versions of libexpat (e.g. expat 1.95.8 on RHEL5) fail here.
my $probe = XML::Parser->new(ParseParamEnt => 1, NoLWP => 1, ErrorContext => 2);
eval { $probe->parse("<?xml version=\"1.0\"?>\n<!DOCTYPE foo SYSTEM \"t/foo.dtd\" []>\n<foo/>\n") };
if ($@) {
    plan skip_all => "expat cannot process external DTD with parameter entities: $@";
}

plan tests => 13;

my $internal_subset = <<'End_of_internal;';
[
  <!ENTITY % foo "IGNORE">
  <!ENTITY % bar "INCLUDE">
  <!ENTITY more SYSTEM "t/ext2.ent">
]
End_of_internal;

my $doc = <<"End_of_doc;";
<?xml version="1.0" encoding="ISO-8859-1"?>
<!DOCTYPE foo SYSTEM "t/foo.dtd"
$internal_subset>
<foo>Happy, happy
<bar>&joy;, &joy;</bar>
<ext/>
&more;
</foo>
End_of_doc;

my $bartxt          = '';
my $internal_exists = 0;

sub start {
    my ( $xp, $el, %atts ) = @_;

    if ( $el eq 'foo' ) {
        ok( !defined $atts{top} );
        ok( defined $atts{zz} );
    }
    elsif ( $el eq 'bar' ) {
        is( $atts{xyz}, 'b' );
    }
    elsif ( $el eq 'ext' ) {
        is( $atts{type}, 'flag' );
    }
    elsif ( $el eq 'more' ) {
        pass("got 'more'");
    }
}

sub char {
    my ( $xp, $text ) = @_;

    $bartxt .= $text if $xp->current_element eq 'bar';
}

sub attl {
    my ( $xp, $el, $att, $type, $dflt, $fixed ) = @_;

    ok( ( $att eq 'xyz' and $dflt eq "'b'" ), 'when el eq bar' ) if ( $el eq 'bar' );
    ok( !( $att eq 'top' and $dflt eq '"hello"' ), 'when el eq foo' ) if ( $el eq 'foo' );
}

sub dtd {
    my ( $xp, $name, $sysid, $pubid, $internal ) = @_;

    pass("doctype called");
    $internal_exists = $internal;
}

my $p = new XML::Parser(
    ParseParamEnt => 1,
    ErrorContext  => 2,
    Handlers      => {
        Start   => \&start,
        Char    => \&char,
        Attlist => \&attl,
        Doctype => \&dtd
    }
);

eval { $p->parse($doc) };

if ( $] < 5.006 ) {
    is( $bartxt, "\xe5\x83\x96, \xe5\x83\x96" );
}
else {
    is( $bartxt, chr(0x50d6) . ", " . chr(0x50d6) );
}

ok( $internal_exists, 'internal exists' );

$doc =~ s/[\s\n]+\[[^]]*\][\s\n]+//m;

$p->setHandlers(
    Start => sub {
        my ( $xp, $el, %atts ) = @_;
        if ( $el eq 'foo' ) {
            ok( defined( $atts{zz} ) );
        }
    }
);

$p->parse($doc);
