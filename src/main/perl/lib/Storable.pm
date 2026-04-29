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
    store_fd nstore_fd fd_retrieve retrieve_fd
    lock_store lock_retrieve lock_nstore
    file_magic read_magic
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

# File locking stubs — JVM handles file access safely; these delegate to
# the non-locking variants so that modules requiring them (e.g. XML::Simple
# cache tests) don't fail.
sub lock_store    { goto &store }
sub lock_nstore   { goto &nstore }
sub lock_retrieve { goto &retrieve }

# Compatibility flag constants used by upstream Storable.pm and its tests
# (lock.t, flags.t, retrieve.t). Values copied from
# perl5/dist/Storable/lib/Storable.pm.
sub BLESS_OK     () { 2 }
sub TIE_OK       () { 4 }
sub FLAGS_COMPAT () { BLESS_OK | TIE_OK }
sub CAN_FLOCK    () { 1 }     # JVM provides advisory locking via FileChannel

# `mretrieve` — retrieve from an in-memory frozen string. Upstream
# Storable's XS exposes this; we expose it as a thin wrapper around
# `thaw` that ignores the optional `flags` argument (we don't honor
# BLESS_OK/TIE_OK gating yet).
sub mretrieve {
    my ($frozen, undef) = @_;
    return thaw($frozen);
}

# Binary-format version constants used by upstream Storable.pm and
# tests that introspect the wire format (file_magic.t etc.). Values
# match the constants in src/main/java/.../storable/Opcodes.java.
sub BIN_MAJOR        () { 2 }
sub BIN_MINOR        () { 12 }
sub BIN_WRITE_MINOR  () { 12 }
sub BIN_VERSION_NV       { sprintf "%d.%03d", BIN_MAJOR(), BIN_MINOR() }
sub BIN_WRITE_VERSION_NV { sprintf "%d.%03d", BIN_MAJOR(), BIN_WRITE_MINOR() }

# File-handle variants of store / retrieve. Upstream's XS implements
# these directly; for our purposes we serialize the value through
# freeze/thaw and read/write the resulting bytes from/to the handle.
sub store_fd {
    my ($self, $fh) = @_;
    require Carp;
    Carp::croak("not a reference") unless ref($self);
    Carp::croak("not a valid file descriptor") unless defined fileno($fh);
    my $bytes = freeze($self);
    # store_fd writes a `pst0` file, so prepend the file header. Easier:
    # call our store() to a temp file, then slurp & write to the handle.
    require File::Temp;
    my ($th, $tmp) = File::Temp::tempfile();
    close $th;
    my $ok = store($self, $tmp) or do { unlink $tmp; return undef };
    open my $rh, '<:raw', $tmp or do { unlink $tmp; return undef };
    local $/;
    my $data = <$rh>;
    close $rh;
    unlink $tmp;
    binmode $fh;
    print {$fh} $data;
    return 1;
}

sub nstore_fd {
    my ($self, $fh) = @_;
    require Carp;
    Carp::croak("not a reference") unless ref($self);
    Carp::croak("not a valid file descriptor") unless defined fileno($fh);
    require File::Temp;
    my ($th, $tmp) = File::Temp::tempfile();
    close $th;
    my $ok = nstore($self, $tmp) or do { unlink $tmp; return undef };
    open my $rh, '<:raw', $tmp or do { unlink $tmp; return undef };
    local $/;
    my $data = <$rh>;
    close $rh;
    unlink $tmp;
    binmode $fh;
    print {$fh} $data;
    return 1;
}

sub fd_retrieve {
    my ($fh, $flags) = @_;
    require Carp;
    Carp::croak("not a valid file descriptor") unless defined fileno($fh);
    binmode $fh;
    require File::Temp;
    my ($th, $tmp) = File::Temp::tempfile();
    binmode $th;
    local $/;
    my $data = <$fh>;
    print {$th} $data;
    close $th;
    my $r = retrieve($tmp);
    unlink $tmp;
    return $r;
}

sub retrieve_fd { &fd_retrieve }    # backward-compat alias

# file_magic / read_magic / show_file_magic — header introspection
# helpers used by tests and a few CPAN modules. Logic ported verbatim
# from perl5/dist/Storable/lib/Storable.pm so behavior matches upstream
# exactly.
sub file_magic {
    my $file = shift;
    open(my $fh, '<', $file) or die "Can't open '$file': $!";
    binmode($fh);
    defined(sysread($fh, my $buf, 32)) or die "Can't read from '$file': $!";
    close($fh);
    $file = "./$file" unless $file;
    return read_magic($buf, $file);
}

sub read_magic {
    my ($buf, $file) = @_;
    my %info;
    my $buflen = length($buf);
    my $magic;
    if ($buf =~ s/^(pst0|perl-store)//) {
        $magic = $1;
        $info{file} = $file || 1;
    } else {
        return undef if $file;
        $magic = "";
    }
    return undef unless length($buf);

    my $net_order;
    if ($magic eq "perl-store" && ord(substr($buf, 0, 1)) > 1) {
        $info{version} = -1;
        $net_order = 0;
    } else {
        $buf =~ s/(.)//s;
        my $major = (ord $1) >> 1;
        return undef if $major > 4;
        $info{major} = $major;
        $net_order = (ord $1) & 0x01;
        if ($major > 1) {
            return undef unless $buf =~ s/(.)//s;
            my $minor = ord $1;
            $info{minor} = $minor;
            $info{version} = "$major.$minor";
            $info{version_nv} = sprintf "%d.%03d", $major, $minor;
        } else {
            $info{version} = $major;
        }
    }
    $info{version_nv} ||= $info{version};
    $info{netorder} = $net_order;

    unless ($net_order) {
        return undef unless $buf =~ s/(.)//s;
        my $len = ord $1;
        return undef unless length($buf) >= $len;
        return undef unless $len == 4 || $len == 8;
        @info{qw(byteorder intsize longsize ptrsize)}
            = unpack "a${len}CCC", $buf;
        (substr $buf, 0, $len + 3) = '';
        if ($info{version_nv} >= 2.002) {
            return undef unless $buf =~ s/(.)//s;
            $info{nvsize} = ord $1;
        }
    }
    $info{hdrsize} = $buflen - length($buf);
    return \%info;
}

sub show_file_magic {
    print <<"EOM";
#
# To recognize the data files of the Perl module Storable,
# the following lines need to be added to the local magic(5) file,
# usually either /usr/share/misc/magic or /etc/magic.
#
0       string  perl-store      perl Storable(v0.6) data
>4      byte    >0              (net-order %d)
>>4     byte    &01             (network-ordered)
>>4     byte    =3              (major 1)
>>4     byte    =2              (major 1)

0       string  pst0            perl Storable(v0.7) data
>4      byte    >0
>>4     byte    &01             (network-ordered)
>>4     byte    =5              (major 2)
>>4     byte    =4              (major 2)
>>5     byte    >0              (minor %d)
EOM
}

1;
