package Digest::SHA;
use strict;
use warnings;

our $VERSION = '6.04';

use Exporter;
our @ISA = qw(Exporter);

XSLoader::load( 'Digest::SHA' );

# NOTE: Core functionality is implemented in:
#       src/main/java/org/perlonjava/perlmodule/DigestSha.java

# Export SHA functions
our @SHA_FUNCTIONS = qw(
    sha1        sha1_hex        sha1_base64
    sha224      sha224_hex      sha224_base64
    sha256      sha256_hex      sha256_base64
    sha384      sha384_hex      sha384_base64
    sha512      sha512_hex      sha512_base64
    sha512224   sha512224_hex   sha512224_base64
    sha512256   sha512256_hex   sha512256_base64
);

our @EXPORT_OK = @SHA_FUNCTIONS;
our %EXPORT_TAGS = (
    all => [@SHA_FUNCTIONS],
);

# Algorithm validation
my %VALID_ALGORITHMS = map { $_ => 1 } qw(
    1 224 256 384 512 512224 512256
    sha1 sha224 sha256 sha384 sha512 sha512224 sha512256
    SHA1 SHA224 SHA256 SHA384 SHA512 SHA512224 SHA512256
);

sub new {
    my ($class, $algorithm) = @_;
    
    $algorithm //= '1';  # Default to SHA-1
    
    # Validate algorithm
    unless ($VALID_ALGORITHMS{$algorithm}) {
        die "Digest::SHA::new: invalid algorithm '$algorithm'";
    }
    
    my $self = bless {
        algorithm => $algorithm,
    }, $class;
    
    return $self;
}

sub algorithm {
    my $self = shift;
    return $self->{algorithm};
}

sub hashsize {
    my $self = shift;
    my $alg = $self->{algorithm};
    
    # Return hash size in bytes
    my %sizes = (
        '1'       => 20,  # SHA-1
        'sha1'    => 20,
        'SHA1'    => 20,
        '224'     => 28,  # SHA-224
        'sha224'  => 28,
        'SHA224'  => 28,
        '256'     => 32,  # SHA-256
        'sha256'  => 32,
        'SHA256'  => 32,
        '384'     => 48,  # SHA-384
        'sha384'  => 48,
        'SHA384'  => 48,
        '512'     => 64,  # SHA-512
        'sha512'  => 64,
        'SHA512'  => 64,
        '512224'  => 28,  # SHA-512/224
        'sha512224' => 28,
        'SHA512224' => 28,
        '512256'  => 32,  # SHA-512/256
        'sha512256' => 32,
        'SHA512256' => 32,
    );
    
    return $sizes{$alg} || 0;
}

# Alias methods for compatibility
sub base64digest {
    my $self = shift;
    return $self->b64digest(@_);
}

# Functional interface implementations
sub sha1 {
    my $data = shift;
    my $sha = Digest::SHA->new('1');
    $sha->add($data);
    return $sha->digest;
}

sub sha1_hex {
    my $data = shift;
    my $sha = Digest::SHA->new('1');
    $sha->add($data);
    return $sha->hexdigest;
}

sub sha1_base64 {
    my $data = shift;
    my $sha = Digest::SHA->new('1');
    $sha->add($data);
    return $sha->b64digest;
}

sub sha224 {
    my $data = shift;
    my $sha = Digest::SHA->new('224');
    $sha->add($data);
    return $sha->digest;
}

sub sha224_hex {
    my $data = shift;
    my $sha = Digest::SHA->new('224');
    $sha->add($data);
    return $sha->hexdigest;
}

sub sha224_base64 {
    my $data = shift;
    my $sha = Digest::SHA->new('224');
    $sha->add($data);
    return $sha->b64digest;
}

sub sha256 {
    my $data = shift;
    my $sha = Digest::SHA->new('256');
    $sha->add($data);
    return $sha->digest;
}

sub sha256_hex {
    my $data = shift;
    my $sha = Digest::SHA->new('256');
    $sha->add($data);
    return $sha->hexdigest;
}

sub sha256_base64 {
    my $data = shift;
    my $sha = Digest::SHA->new('256');
    $sha->add($data);
    return $sha->b64digest;
}

sub sha384 {
    my $data = shift;
    my $sha = Digest::SHA->new('384');
    $sha->add($data);
    return $sha->digest;
}

sub sha384_hex {
    my $data = shift;
    my $sha = Digest::SHA->new('384');
    $sha->add($data);
    return $sha->hexdigest;
}

sub sha384_base64 {
    my $data = shift;
    my $sha = Digest::SHA->new('384');
    $sha->add($data);
    return $sha->b64digest;
}

sub sha512 {
    my $data = shift;
    my $sha = Digest::SHA->new('512');
    $sha->add($data);
    return $sha->digest;
}

sub sha512_hex {
    my $data = shift;
    my $sha = Digest::SHA->new('512');
    $sha->add($data);
    return $sha->hexdigest;
}

sub sha512_base64 {
    my $data = shift;
    my $sha = Digest::SHA->new('512');
    $sha->add($data);
    return $sha->b64digest;
}

sub sha512224 {
    my $data = shift;
    my $sha = Digest::SHA->new('512224');
    $sha->add($data);
    return $sha->digest;
}

sub sha512224_hex {
    my $data = shift;
    my $sha = Digest::SHA->new('512224');
    $sha->add($data);
    return $sha->hexdigest;
}

sub sha512224_base64 {
    my $data = shift;
    my $sha = Digest::SHA->new('512224');
    $sha->add($data);
    return $sha->b64digest;
}

sub sha512256 {
    my $data = shift;
    my $sha = Digest::SHA->new('512256');
    $sha->add($data);
    return $sha->digest;
}

sub sha512256_hex {
    my $data = shift;
    my $sha = Digest::SHA->new('512256');
    $sha->add($data);
    return $sha->hexdigest;
}

sub sha512256_base64 {
    my $data = shift;
    my $sha = Digest::SHA->new('512256');
    $sha->add($data);
    return $sha->b64digest;
}

# File hashing utilities
sub shasum {
    my ($filename, $algorithm) = @_;
    $algorithm //= '256';
    
    my $sha = Digest::SHA->new($algorithm);
    $sha->addfile($filename);
    return $sha->hexdigest;
}

# HMAC functionality (simplified)
sub hmac_sha1 {
    my ($data, $key) = @_;
    return _hmac($data, $key, '1');
}

sub hmac_sha1_hex {
    my ($data, $key) = @_;
    my $hmac = _hmac($data, $key, '1');
    return unpack('H*', $hmac);
}

sub hmac_sha1_base64 {
    my ($data, $key) = @_;
    my $hmac = _hmac($data, $key, '1');
    require MIME::Base64;
    return MIME::Base64::encode_base64($hmac, '');
}

sub hmac_sha256 {
    my ($data, $key) = @_;
    return _hmac($data, $key, '256');
}

sub hmac_sha256_hex {
    my ($data, $key) = @_;
    my $hmac = _hmac($data, $key, '256');
    return unpack('H*', $hmac);
}

sub hmac_sha256_base64 {
    my ($data, $key) = @_;
    my $hmac = _hmac($data, $key, '256');
    require MIME::Base64;
    return MIME::Base64::encode_base64($hmac, '');
}

# Simple HMAC implementation
sub _hmac {
    my ($data, $key, $algorithm) = @_;
    
    my $sha = Digest::SHA->new($algorithm);
    my $blocksize = $algorithm eq '384' || $algorithm eq '512' ? 128 : 64;
    
    # Adjust key length
    if (length($key) > $blocksize) {
        $sha->add($key);
        $key = $sha->digest;
        $sha->reset;
    }
    
    # Pad key to blocksize
    $key .= "\x00" x ($blocksize - length($key));
    
    # Create inner and outer padding
    my $ipad = $key ^ ("\x36" x $blocksize);
    my $opad = $key ^ ("\x5c" x $blocksize);
    
    # Inner hash
    $sha->add($ipad);
    $sha->add($data);
    my $inner_hash = $sha->digest;
    $sha->reset;
    
    # Outer hash
    $sha->add($opad);
    $sha->add($inner_hash);
    return $sha->digest;
}

1;

__END__

=head1 NAME

Digest::SHA - Perl extension for SHA-1/224/256/384/512

=head1 SYNOPSIS

  # Functional interface
  use Digest::SHA qw(sha1 sha1_hex sha1_base64 sha256_hex);
  
  $digest = sha1($data);
  $digest = sha1_hex($data);
  $digest = sha1_base64($data);
  $digest = sha256_hex($data);

  # Object-oriented interface
  use Digest::SHA;
  
  $sha = Digest::SHA->new($alg);
  $sha->add($data);              # feed data into stream
  $sha->addfile(*F);
  $sha->addfile($filename);
  $sha->add_bits($bits);
  $sha->add_bits($data, $nbits);
  $sha_copy = $sha->clone;       # make copy of digest object
  $state = $sha->getstate;       # save current state to string
  $sha->putstate($state);        # restore previous $state
  $digest = $sha->digest;        # compute digest
  $digest = $sha->hexdigest;
  $digest = $sha->b64digest;

=head1 DESCRIPTION

Digest::SHA is a complete implementation of the NIST Secure Hash Standard.
It gives Perl programmers a convenient way to calculate SHA-1, SHA-224,
SHA-256, SHA-384, SHA-512, SHA-512/224, and SHA-512/256 message digests.

This is a PerlOnJava implementation that uses Java's MessageDigest internally.

=head1 METHODS

=head2 new($algorithm)

Create a new Digest::SHA object. The algorithm parameter specifies which
variant to use (1, 224, 256, 384, 512, 512224, 512256).

=head2 add($data, ...)

Add data to the hash. Multiple arguments are concatenated.

=head2 addfile($file)

Add the contents of a file to the hash.

=head2 add_bits($bits)

Add bits to the hash. Can be called with a bit string or data and bit count.

=head2 digest

Compute the hash and return it in binary form.

=head2 hexdigest

Compute the hash and return it as a hex string.

=head2 b64digest

Compute the hash and return it as a base64 string.

=head2 clone

Make a copy of the digest object.

=head2 getstate / putstate

Save and restore the internal state.

=head2 reset

Reset the digest to its initial state.

=head1 FUNCTIONS

The module exports many convenience functions:

  sha1($data)         sha1_hex($data)         sha1_base64($data)
  sha224($data)       sha224_hex($data)       sha224_base64($data)
  sha256($data)       sha256_hex($data)       sha256_base64($data)
  sha384($data)       sha384_hex($data)       sha384_base64($data)
  sha512($data)       sha512_hex($data)       sha512_base64($data)
  sha512224($data)    sha512224_hex($data)    sha512224_base64($data)
  sha512256($data)    sha512256_hex($data)    sha512256_base64($data)

=head1 HMAC FUNCTIONS

Basic HMAC functionality is also provided:

  hmac_sha1($data, $key)
  hmac_sha1_hex($data, $key)
  hmac_sha1_base64($data, $key)
  hmac_sha256($data, $key)
  hmac_sha256_hex($data, $key)
  hmac_sha256_base64($data, $key)

=head1 SEE ALSO

L<Digest>, L<Digest::SHA::PurePerl>

=head1 AUTHOR

PerlOnJava implementation based on the original Digest::SHA by Mark Shelor.

=cut

