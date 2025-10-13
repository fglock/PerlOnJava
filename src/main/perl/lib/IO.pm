package IO;

use strict;
use warnings;

our $VERSION = '1.55';

# Export constants for seek operations
use constant SEEK_SET => 0;
use constant SEEK_CUR => 1;
use constant SEEK_END => 2;

# Export constants for setvbuf operations
use constant _IOFBF => 0;  # Fully buffered
use constant _IOLBF => 1;  # Line buffered
use constant _IONBF => 2;  # Unbuffered

# Make constants available for export
require Exporter;
our @ISA = qw(Exporter);
our @EXPORT = qw(
    SEEK_SET
    SEEK_CUR
    SEEK_END
    _IOFBF
    _IOLBF
    _IONBF
);

# Load the Java IOHandle module which provides ungetc and other XS methods
BEGIN {
    eval {
        require XSLoader;
        XSLoader::load('IO::Handle');
    };
}

1;

__END__

=head1 NAME

IO - Perl core IO modules

=head1 SYNOPSIS

    use IO;

=head1 DESCRIPTION

C<IO> provides a simple mechanism to load several of the IO modules
in one go.  The IO modules are:

      IO::Handle
      IO::Seekable
      IO::File
      IO::Pipe
      IO::Socket
      IO::Dir

This module also provides constants for seek operations and buffering modes.

=head1 CONSTANTS

=over 4

=item SEEK_SET, SEEK_CUR, SEEK_END

Constants for use with seek operations.

=item _IOFBF, _IOLBF, _IONBF

Constants for use with setvbuf operations (fully buffered, line buffered, unbuffered).

=back

=cut
