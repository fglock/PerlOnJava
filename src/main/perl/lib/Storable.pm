package Storable;

use strict;
use warnings;
use XSLoader;

our $VERSION = '3.32';  # Match Perl's Storable version

# Load the Java implementation
XSLoader::load('Storable', $VERSION);

# Export the main functions
use Exporter 'import';
our @EXPORT = qw(
    store retrieve nstore
    freeze thaw nfreeze
    dclone
);

our @EXPORT_OK = qw(
    store_fd fd_retrieve
    lock_store lock_retrieve lock_nstore
);

our %EXPORT_TAGS = (
    'all' => [@EXPORT, @EXPORT_OK],
);

# Configuration variables (matching Perl's Storable)
our $canonical = 0;
our $forgive_me = 0;
our $Deparse = 0;
our $Eval = 0;
our $restrict_universe = 0;

=head1 NAME

Storable - Persistent storage and retrieval of Perl data structures

=head1 SYNOPSIS

    use Storable;
    
    # Store to file
    store \%hash, 'file.dat';
    $hashref = retrieve('file.dat');
    
    # Store to memory
    $frozen = freeze(\%hash);
    $hashref = thaw($frozen);
    
    # Network byte order
    $frozen = nfreeze(\%hash);
    nstore \%hash, 'file.dat';
    
    # Deep cloning
    $clone = dclone(\%hash);

=head1 DESCRIPTION

This is the PerlOnJava implementation of Perl's Storable module.
It provides persistent storage and retrieval of Perl data structures
using YAML with type tags for blessed objects and built-in circular
reference handling.

Key features:
- Full support for blessed objects using class names
- Circular reference detection and handling
- Cross-platform compatibility with network byte order
- Human-readable YAML format for stored files
- Binary compression for freeze/thaw operations

=head1 FUNCTIONS

=head2 store($data, $filename)

Stores a data structure to a file in YAML format.

=head2 retrieve($filename)

Retrieves a data structure from a file.

=head2 nstore($data, $filename)

Stores a data structure to a file using network byte order (same as store in this implementation).

=head2 freeze($data)

Serializes a data structure to a binary string using YAML + GZIP compression.

=head2 nfreeze($data)

Serializes a data structure to a binary string using network byte order (same as freeze).

=head2 thaw($frozen)

Deserializes a binary string back to a data structure.

=head2 dclone($data)

Creates a deep copy of a data structure.

=head1 BLESSED OBJECTS

This implementation fully supports blessed objects by storing the class name
and automatically re-blessing objects during deserialization. Objects are
serialized using YAML type tags in the format C<!!perl/hash:ClassName>.

=head1 CIRCULAR REFERENCES

Circular references are automatically detected and handled using YAML's
built-in anchor and alias system. No special configuration is required.

=head1 COMPATIBILITY

This implementation aims to be compatible with Perl's standard Storable module
while leveraging modern serialization techniques for better maintainability
and debuggability.

=head1 SEE ALSO

L<YAML::PP>, L<Data::Dumper>

=head1 AUTHOR

PerlOnJava Project

=cut

1;
