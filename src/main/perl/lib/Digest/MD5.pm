package Digest::MD5;
use strict;
use warnings;

our $VERSION = '2.58';

use Exporter;
our @ISA = qw(Exporter);

XSLoader::load( 'Digest::MD5' );

# NOTE: Core functionality is implemented in:
#       src/main/java/org/perlonjava/perlmodule/DigestMD5.java

# Export MD5 functions
our @MD5_FUNCTIONS = qw(
    md5 md5_hex md5_base64
);

our @EXPORT_OK = @MD5_FUNCTIONS;
our %EXPORT_TAGS = (
    all => [@MD5_FUNCTIONS],
);

sub new {
    my ($class_or_instance) = @_;

    # If called as instance method, just reset
    if (ref $class_or_instance) {
        $class_or_instance->reset();
        return $class_or_instance;
    }

    # Otherwise create new instance
    my $self = bless {
        algorithm => 'MD5',
    }, $class_or_instance;

    return $self;
}

sub reset {
    my $self = shift;
    # Clear any cached state
    delete $self->{_MessageDigest};
    return $self;
}

# Functional interface implementations
sub md5 {
    my $data = join('', @_);
    my $md5 = Digest::MD5->new();
    $md5->add($data);
    return $md5->digest;
}

sub md5_hex {
    my $data = join('', @_);
    my $md5 = Digest::MD5->new();
    $md5->add($data);
    return $md5->hexdigest;
}

sub md5_base64 {
    my $data = join('', @_);
    my $md5 = Digest::MD5->new();
    $md5->add($data);
    return $md5->b64digest;
}

1;

__END__

=head1 NAME

Digest::MD5 - Perl interface to the MD5 Algorithm

=head1 SYNOPSIS

  # Functional interface
  use Digest::MD5 qw(md5 md5_hex md5_base64);

  $digest = md5($data);
  $digest = md5_hex($data);
  $digest = md5_base64($data);

  # OO interface
  use Digest::MD5;

  $md5 = Digest::MD5->new;

  $md5->add($data);
  $md5->addfile($file_handle);

  $digest = $md5->digest;
  $digest = $md5->hexdigest;
  $digest = $md5->b64digest;

=head1 DESCRIPTION

The C<Digest::MD5> module allows you to use the RSA Data Security
Inc. MD5 Message Digest algorithm from within Perl programs.  The
algorithm takes as input a message of arbitrary length and produces
as output a 128-bit "fingerprint" or "message digest" of the input.

This is a PerlOnJava implementation that uses Java's MessageDigest internally.

=head1 FUNCTIONS

The following functions are provided by the C<Digest::MD5> module.
None of these functions are exported by default.

=over 4

=item md5($data,...)

This function will concatenate all arguments, calculate the MD5 digest
of this "message", and return it in binary form.  The returned string
will be 16 bytes long.

=item md5_hex($data,...)

Same as md5(), but will return the digest in hexadecimal form. The
length of the returned string will be 32 and it will only contain
characters from this set: '0'..'9' and 'a'..'f'.

=item md5_base64($data,...)

Same as md5(), but will return the digest as a base64 encoded string.
The length of the returned string will be 22 and it will only contain
characters from this set: 'A'..'Z', 'a'..'z', '0'..'9', '+' and '/'.

Note that the base64 encoded string returned is not padded to be a
multiple of 4 bytes long.  If you want interoperability with other
base64 encoded md5 digests you might want to append the redundant
string "==" to the result.

=back

=head1 METHODS

The object oriented interface to C<Digest::MD5> is described in this
section.  After a C<Digest::MD5> object has been created, you will add
data to it and finally ask for the digest in a suitable format.  A
single object can be used to calculate multiple digests.

The following methods are provided:

=over 4

=item $md5 = Digest::MD5->new

The constructor returns a new C<Digest::MD5> object which encapsulate
the state of the MD5 message-digest algorithm.

If called as an instance method (i.e. $md5->new) it will just reset the
state the object to the state of a newly created object.  No new object
is created in this case.

=item $md5->reset

This is just an alias for $md5->new.

=item $md5->clone

This a copy of the $md5 object. It is useful when you do not want to
destroy the digests state, but need an intermediate value of the
digest, e.g. when calculating digests iteratively on a continuous data
stream.

=item $md5->add($data,...)

The $data provided as argument are appended to the message we
calculate the digest for.  The return value is the $md5 object itself.

=item $md5->addfile($io_handle)

The $io_handle will be read until EOF and its content appended to the
message we calculate the digest for.  The return value is the $md5
object itself.

=item $md5->add_bits($data, $nbits)

=item $md5->add_bits($bitstring)

Since the MD5 algorithm is byte oriented you might only add bits as
multiples of 8, so you probably want to just use add() instead.  The
add_bits() method is provided for compatibility with other digest
implementations.

=item $md5->digest

Return the binary digest for the message. The returned string will be
16 bytes long.

=item $md5->hexdigest

Same as $md5->digest, but will return the digest in hexadecimal form.

=item $md5->b64digest

Same as $md5->digest, but will return the digest as a base64 encoded
string.

=item @ctx = $md5->context

=item $md5->context(@ctx)

Saves or restores the internal state. When called with no arguments,
returns a list: number of blocks processed, a 16-byte internal state
buffer, then optionally up to 63 bytes of unprocessed data if there
are any. When passed those same arguments, restores the state.

=back

=head1 SEE ALSO

L<Digest>

=head1 AUTHOR

PerlOnJava implementation based on the original Digest::MD5 by Gisle Aas.

=cut