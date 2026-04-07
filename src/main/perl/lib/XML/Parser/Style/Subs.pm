# $Id: Subs.pm,v 1.1 2003-07-27 16:07:49 matt Exp $

package XML::Parser::Style::Subs;
use strict;

sub Start {
    no strict 'refs';
    my $expat = shift;
    my $tag   = shift;
    my $fname = $expat->{Pkg} . "::$tag";
    if ( defined &$fname ) {
        ( \&$fname )->( $expat, $tag, @_ );
    }
}

sub End {
    no strict 'refs';
    my $expat = shift;
    my $tag   = shift;
    my $fname = $expat->{Pkg} . "::${tag}_";
    if ( defined &$fname ) {
        ( \&$fname )->( $expat, $tag );
    }
}

1;
__END__

=head1 NAME

XML::Parser::Style::Subs - glue for handling element callbacks

=head1 SYNOPSIS

  use XML::Parser;
  my $p = XML::Parser->new(Style => 'Subs', Pkg => 'MySubs');
  $p->parsefile('foo.xml');
  
  {
    package MySubs;
    
    sub foo {
      # start of foo tag
    }
    
    sub foo_ {
      # end of foo tag
    }
  }

=head1 DESCRIPTION

Each time an element starts, a sub by that name in the package specified
by the Pkg option is called with the same parameters that the Start
handler gets called with.

Each time an element ends, a sub with that name appended with an underscore
("_"), is called with the same parameters that the End handler gets called
with.

Nothing special is returned by parse.

=cut
