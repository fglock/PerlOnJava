package String::CRC32;

use strict;
use warnings;

our $VERSION = '2.100';

use Exporter 'import';
our @EXPORT = qw(crc32);
our @EXPORT_OK = @EXPORT;

use XSLoader;
XSLoader::load('String::CRC32', $VERSION);

1;

__END__

=head1 NAME

String::CRC32 - Perl interface for CRC-32 checksums

=head1 DESCRIPTION

This is a PerlOnJava port of String::CRC32. It calculates ZIP-compatible
CRC-32 checksums for byte strings and filehandles, with optional incremental
checksum seeds. The implementation uses Java's CRC-32 support and the shared
seeded CRC routine used by Compress::Zlib.

=head1 AUTHOR

Soenke J. Peters <peters__perl@opcenter.de>

Current maintainer: Lee Johnson (LEEJO)

PerlOnJava Java implementation by Flavio S. Glock.

=head1 COPYRIGHT AND LICENSE

The original module author released this package into the public domain.
The CRC algorithm code is credited to Craig Bruce; the module interface was
inspired by String::CRC by David Sharnoff and Matthew Dillon.

=cut
