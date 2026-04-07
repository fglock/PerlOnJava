use strict;
use warnings;
use Test::More tests => 4;

use XML::Parser;

my $cmnt_count    = 0;
my $pi_count      = 0;
my $between_count = 0;
my $authseen      = 0;

sub init {
    my $xp = shift;
    $xp->skip_until(1);    # Skip through prolog
}

sub proc {
    $pi_count++;
}

sub cmnt {
    $cmnt_count++;
}

sub start {
    my ( $xp, $el ) = @_;
    my $ndx = $xp->element_index;
    if ( !$authseen and $el eq 'authlist' ) {
        $authseen = 1;
        $xp->skip_until(2000);
    }
    elsif ( $authseen and $ndx < 2000 ) {
        $between_count++;
    }
}

my $p = XML::Parser->new(
    Handlers => {
        Init    => \&init,
        Start   => \&start,
        Comment => \&cmnt,
        Proc    => \&proc
    }
);

$p->parsefile('samples/REC-xml-19980210.xml');

is( $between_count, 0, 'no start events seen between authlist and index 2000' );

is( $pi_count, 0, 'no processing instructions seen (all in prolog, skipped)' );

is( $cmnt_count, 5, 'only 5 comments seen after skip_until points' );

pass('skip_until parsing completed without error');
