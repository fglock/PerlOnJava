package open;

# open pragma — default PerlIO layers (${^OPEN}) and optional :std binmode.
# Mirrors Perl 5's lib/open.pm argument parsing so forms like
#   use open ':std' => 'utf8';
# (bareword layer, no leading colon) apply :utf8 to STDIN/STDOUT/STDERR.
# Without this, standard handles stay in byte mode and "Wide character in print"
# fires for UTF-8 strings even when tests use the same pragma as upstream Perl.

use strict;
use warnings;

our $VERSION = '1.14';

use Carp qw(croak);

sub import {
    my ( $class, @args ) = @_;
    croak "open: needs explicit list of PerlIO layers" unless @args;

    my $std = 0;
    my ( $in, $out );

    while (@args) {
        my $type = shift @args;
        my $dscp;

        # Shorthand: use open ':utf8';  use open ':encoding(UTF-8)';  use open ':locale';
        if ( $type =~ /^:?(utf8|locale|encoding\(.+\))$/ ) {
            $type = 'IO';
            $dscp = ":$1";
        }
        elsif ( $type eq ':std' ) {
            $std = 1;
            next;
        }
        else {
            $dscp = shift @args;
            $dscp = '' unless defined $dscp;
        }

        my @val;
        foreach my $layer ( split( /\s+/, $dscp ) ) {
            next if $layer eq '';
            $layer =~ s/^://;
            push @val, ":$layer";
        }

        if ( $type eq 'IN' ) {
            $in = join( ' ', @val );
        }
        elsif ( $type eq 'OUT' ) {
            $out = join( ' ', @val );
        }
        elsif ( $type eq 'IO' ) {
            $in = $out = join( ' ', @val );
        }
        else {
            croak "Unknown PerlIO layer class '$type' (need IN, OUT or IO)";
        }
    }

    ${^OPEN} = join( "\0", defined $in ? $in : '', defined $out ? $out : '' );
    $^H{'open<'} = $in  if defined $in;
    $^H{'open>'} = $out if defined $out;

    if ($std) {
        binmode( \*STDIN,  $in )  if $in;
        if ($out) {
            binmode( \*STDOUT, $out );
            binmode( \*STDERR, $out );
        }
    }
}

1;

__END__

=head1 NAME

open - perl pragma to set default PerlIO layers for I/O

=head1 SYNOPSIS

 use open ':std' => 'utf8';
 use open ':std', ':encoding(UTF-8)';

=cut
