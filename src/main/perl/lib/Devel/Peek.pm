package Devel::Peek;

#      Devel/Peek.pm
#
#      Original Copyright (c) 1995-2000 Ilya Zakharevich
#
#      You may distribute under the terms of either the GNU General Public
#      License or the Artistic License, as specified in the README file.
#
#      PerlOnJava stub implementation.
#      PerlOnJava implementation by Flavio S. Glock.
#
# Minimal Devel::Peek for PerlOnJava.
# The JVM uses tracing GC, not reference counting, so SV internals
# are not directly accessible.  This stub provides enough for modules
# like Params::Validate whose tests use SvREFCNT().

use strict;
use warnings;

our $VERSION = '1.34';

use Exporter 'import';
our @EXPORT    = qw(Dump DumpArray DumpProg);
our @EXPORT_OK = qw(Dump DumpArray DumpProg SvREFCNT DeadCode
                     fill_mstats mstats_fillhash mstats2hash
                     DumpWithOP);

# SvREFCNT - return the reference count of a scalar.
# JVM uses tracing GC, not reference counting.  Return 1 as a safe
# default (the value is "alive" if we can see it).
sub SvREFCNT { return 1 }

# Dump - print internal representation of a Perl value.
# Not meaningful on JVM; emit a short placeholder.
sub Dump {
    my ($sv, $lim) = @_;
    $lim = 4 unless defined $lim;
    my $ref  = ref($sv) || 'SCALAR';
    my $val  = defined $sv ? (ref($sv) ? "$sv" : $sv) : 'undef';
    print STDERR "SV = $ref\n  VALUE = $val\n  (Devel::Peek::Dump stub on PerlOnJava)\n";
}

sub DumpArray {
    my ($lim, @vals) = @_;
    for my $i (0 .. $#vals) {
        print STDERR "Elt No. $i\n";
        Dump($vals[$i], $lim);
    }
}

sub DumpProg   { print STDERR "DumpProg not available on PerlOnJava\n" }
sub DumpWithOP { print STDERR "DumpWithOP not available on PerlOnJava\n" }
sub DeadCode   { return 0 }

sub fill_mstats      { return }
sub mstats_fillhash  { return }
sub mstats2hash      { return }

1;

__END__

=head1 NAME

Devel::Peek - PerlOnJava stub for SV introspection

=head1 DESCRIPTION

This is a stub implementation of Devel::Peek for PerlOnJava.
The JVM uses tracing garbage collection rather than reference counting,
so SV internals are not directly accessible.

C<SvREFCNT()> always returns 1 (the value is alive if reachable).
C<Dump()> prints a short placeholder to STDERR.

=head1 SEE ALSO

L<B>, L<Devel::Peek> (full version on CPAN)

=cut
