package String::Similarity;

use strict;
use warnings;
use Exporter;
use XSLoader;

our $VERSION = '1.04';
our @ISA = qw(Exporter);
our @EXPORT = qw(similarity);
our @EXPORT_OK = qw(fstrcmp);

XSLoader::load('String::Similarity', $VERSION);
*similarity = *fstrcmp;

1;

__END__

=head1 NAME

String::Similarity - calculate the similarity of two strings

=head1 DESCRIPTION

This is the PerlOnJava port of String::Similarity 1.04.  The public Perl API
is preserved and Marc Lehmann's XS entry point is implemented in Java over
Unicode code points.

=head1 COPYRIGHT AND LICENSE

The original module and adaptation are by Marc Lehmann; the underlying
fstrcmp work is credited to Peter Miller and GNU diffutils.  This port is
distributed under the same terms as the original module.

=cut
