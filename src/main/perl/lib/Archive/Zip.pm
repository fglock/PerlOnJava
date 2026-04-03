package Archive::Zip;

use strict;
use warnings;

our $VERSION = '1.68';

# Load Java implementation
use XSLoader;
XSLoader::load('Archive::Zip', $VERSION);

# Export constants and functions
use Exporter 'import';
our @EXPORT_OK = qw(
    AZ_OK AZ_STREAM_END AZ_ERROR AZ_FORMAT_ERROR AZ_IO_ERROR
    COMPRESSION_STORED COMPRESSION_DEFLATED
    COMPRESSION_LEVEL_NONE COMPRESSION_LEVEL_DEFAULT
    COMPRESSION_LEVEL_FASTEST COMPRESSION_LEVEL_BEST_COMPRESSION
);
our %EXPORT_TAGS = (
    ERROR_CODES => [qw(AZ_OK AZ_STREAM_END AZ_ERROR AZ_FORMAT_ERROR AZ_IO_ERROR)],
    CONSTANTS   => [qw(
        COMPRESSION_STORED COMPRESSION_DEFLATED
        COMPRESSION_LEVEL_NONE COMPRESSION_LEVEL_DEFAULT
        COMPRESSION_LEVEL_FASTEST COMPRESSION_LEVEL_BEST_COMPRESSION
    )],
);

# Error handling (matches CPAN Archive::Zip API)
our $ErrorHandler = \&Carp::carp;

sub setErrorHandler {
    my $errorHandler = (ref($_[0]) eq 'HASH') ? shift->{subroutine} : shift;
    $errorHandler = \&Carp::carp unless defined($errorHandler);
    my $oldErrorHandler = $Archive::Zip::ErrorHandler;
    $Archive::Zip::ErrorHandler = $errorHandler;
    return $oldErrorHandler;
}

# For Archive::Zip::Member methods - inherit from Archive::Zip
# This allows member objects to use the same Java methods
package Archive::Zip::Member;
our @ISA = ('Archive::Zip');

1;

__END__

=head1 NAME

Archive::Zip - Provide an interface to ZIP archive files

=head1 SYNOPSIS

    use Archive::Zip qw( :ERROR_CODES :CONSTANTS );

    # Read a zip file
    my $zip = Archive::Zip->new();
    my $status = $zip->read('archive.zip');
    die 'read error' unless $status == AZ_OK;

    # List members
    my @members = $zip->memberNames();

    # Extract a file
    $status = $zip->extractMember('file.txt', 'output.txt');

    # Create a new zip
    my $zip = Archive::Zip->new();
    $zip->addFile('file.txt');
    $zip->addString('hello', 'hello.txt');
    $zip->writeToFileNamed('output.zip');

=head1 DESCRIPTION

This is a port of the CPAN Archive::Zip module for PerlOnJava.

It provides an interface to ZIP archive files using Java's built-in
java.util.zip package.

=head1 METHODS

=head2 Archive Methods

=over 4

=item new( [$filename] )

Create a new Archive::Zip object. If a filename is provided, reads
the zip file.

=item read( $filename )

Read a zip file. Returns AZ_OK on success.

=item writeToFileNamed( $filename )

Write the archive to a file. Returns AZ_OK on success.

=item writeToFileHandle( $fh )

Write the archive to a filehandle. Returns AZ_OK on success.

=item members()

Returns a list of Archive::Zip::Member objects.

=item memberNames()

Returns a list of member names.

=item numberOfMembers()

Returns the number of members.

=item memberNamed( $name )

Returns the member with the given name, or undef.

=item membersMatching( $regex )

Returns members whose names match the regex.

=item addFile( $filename [, $memberName] )

Add a file to the archive. Returns the member object.

=item addString( $content, $memberName )

Add a string as a member. Returns the member object.

=item addDirectory( $dirname )

Add a directory entry. Returns the member object.

=item extractMember( $memberName [, $destName] )

Extract a member to a file. Returns AZ_OK on success.

=item extractMemberWithoutPaths( $member [, $destDir] )

Extract a member without path components. Returns AZ_OK on success.

=item extractTree( [$root [, $dest]] )

Extract all members to a directory. Returns AZ_OK on success.

=item removeMember( $member )

Remove a member from the archive. Returns the removed member.

=back

=head2 Member Methods

=over 4

=item fileName()

Returns the member's file name.

=item contents()

Returns the member's contents.

=item isDirectory()

Returns true if the member is a directory.

=item uncompressedSize()

Returns the uncompressed size.

=item compressedSize()

Returns the compressed size.

=item compressionMethod()

Returns the compression method (COMPRESSION_STORED or COMPRESSION_DEFLATED).

=item lastModTime()

Returns the last modification time (Unix timestamp).

=item crc32()

Returns the CRC32 checksum.

=item externalFileName()

Returns the external file name (for members added from files).

=back

=head1 CONSTANTS

=head2 Error Codes

=over 4

=item AZ_OK (0)

Success.

=item AZ_STREAM_END (1)

End of stream.

=item AZ_ERROR (2)

Generic error.

=item AZ_FORMAT_ERROR (3)

Format error.

=item AZ_IO_ERROR (4)

I/O error.

=back

=head2 Compression Methods

=over 4

=item COMPRESSION_STORED (0)

No compression.

=item COMPRESSION_DEFLATED (8)

Deflate compression.

=back

=head2 Compression Levels

=over 4

=item COMPRESSION_LEVEL_NONE (0)

=item COMPRESSION_LEVEL_DEFAULT (-1)

=item COMPRESSION_LEVEL_FASTEST (1)

=item COMPRESSION_LEVEL_BEST_COMPRESSION (9)

=back

=head1 AUTHOR

Original Author: Ned Konz, perl@bike-hierarchical.com

This is a port for PerlOnJava using Java's java.util.zip package.

=head1 COPYRIGHT AND LICENSE

Original Archive::Zip Copyright (c) 2000-2017, Various Authors.

This module is free software; you may distribute it under the same terms
as Perl itself.

=cut
