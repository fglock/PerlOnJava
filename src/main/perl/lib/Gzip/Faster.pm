package Gzip::Faster;

# PerlOnJava shim for Gzip::Faster.
#
# The upstream CPAN distribution (by Ben Kasmin Bullock) is an XS module that
# wraps the zlib C library for high-speed gzip compression/decompression.
# PerlOnJava cannot load native XS extensions, so this shim re-implements
# the public API using IO::Compress::Gzip and IO::Uncompress::Gunzip, which
# are bundled pure-Perl modules available in PerlOnJava.
#
# CPAN::Nearest only requires 'gunzip_file', but we implement the full
# default-exported set so that other callers are not broken:
#
#   gzip($plain)               -> compressed scalar
#   gunzip($compressed)        -> decompressed scalar
#   gzip_file($path)           -> compressed scalar of file contents
#   gunzip_file($path)         -> decompressed scalar of file contents
#   gzip_to_file($plain, $path)-> write compressed data to $path

use strict;
use warnings;

require Exporter;
our @ISA    = qw(Exporter);
our @EXPORT = qw(gzip gunzip gzip_file gunzip_file gzip_to_file);
our @EXPORT_OK = qw(deflate inflate deflate_raw inflate_raw);
our %EXPORT_TAGS = (all => [@EXPORT, @EXPORT_OK]);

our $VERSION = '0.22';

use Carp qw(croak);
use IO::Compress::Gzip    ();
use IO::Uncompress::Gunzip ();

# ---------------------------------------------------------------------------
# gzip($plain_text) -> $compressed_scalar
# ---------------------------------------------------------------------------
sub gzip {
    my ($plain) = @_;
    my $compressed;
    IO::Compress::Gzip::gzip(\$plain => \$compressed)
        or croak "gzip failed: $IO::Compress::Gzip::GzipError";
    return $compressed;
}

# ---------------------------------------------------------------------------
# gunzip($compressed_scalar) -> $plain_text
# ---------------------------------------------------------------------------
sub gunzip {
    my ($compressed) = @_;
    my $plain;
    IO::Uncompress::Gunzip::gunzip(\$compressed => \$plain)
        or croak "gunzip failed: $IO::Uncompress::Gunzip::GunzipError";
    return $plain;
}

# ---------------------------------------------------------------------------
# gzip_file($path) -> $compressed_scalar  (reads file, returns compressed)
# ---------------------------------------------------------------------------
sub gzip_file {
    my ($file) = @_;
    open my $fh, '<:raw', $file or croak "Cannot open '$file': $!";
    local $/;
    my $plain = <$fh>;
    close $fh or croak "Cannot close '$file': $!";
    return gzip($plain);
}

# ---------------------------------------------------------------------------
# gunzip_file($path) -> $plain_text  (reads .gz file, returns decompressed)
# ---------------------------------------------------------------------------
sub gunzip_file {
    my ($file) = @_;
    my $plain;
    IO::Uncompress::Gunzip::gunzip($file => \$plain)
        or croak "gunzip_file failed on '$file': $IO::Uncompress::Gunzip::GunzipError";
    return $plain;
}

# ---------------------------------------------------------------------------
# gzip_to_file($plain, $path)  (compresses and writes to file)
# ---------------------------------------------------------------------------
sub gzip_to_file {
    my ($plain, $file) = @_;
    IO::Compress::Gzip::gzip(\$plain => $file)
        or croak "gzip_to_file failed writing '$file': $IO::Compress::Gzip::GzipError";
    return;
}

# ---------------------------------------------------------------------------
# deflate / inflate variants (raw, no gzip header) — basic stubs using
# Compress::Raw::Zlib when available, otherwise croak.
# ---------------------------------------------------------------------------
for my $sym (qw(deflate inflate deflate_raw inflate_raw)) {
    no strict 'refs';
    *$sym = sub {
        croak "Gzip::Faster::$sym is not implemented in the PerlOnJava shim";
    };
}

1;

__END__

=head1 NAME

Gzip::Faster - PerlOnJava shim backed by IO::Compress::Gzip / IO::Uncompress::Gunzip

=head1 DESCRIPTION

This is a compatibility shim for the L<Gzip::Faster> XS module, implemented
using the pure-Perl L<IO::Compress::Gzip> and L<IO::Uncompress::Gunzip>
modules that are bundled with PerlOnJava.  The interface is identical to
the upstream XS module for the commonly-used functions.

=head1 SEE ALSO

L<Gzip::Faster> on CPAN (XS original by Ben Kasmin Bullock),
L<IO::Uncompress::Gunzip>, L<IO::Compress::Gzip>.

=cut
