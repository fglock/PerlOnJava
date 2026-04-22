package Crypt::OpenSSL::Bignum;

use strict;
use warnings;

our $VERSION = '0.09';

use Exporter;
our @ISA = qw(Exporter);

# Delegate to the Java-backed implementation in
# src/main/java/org/perlonjava/runtime/perlmodule/CryptOpenSSLBignum.java.
use XSLoader;
XSLoader::load('Crypt::OpenSSL::Bignum', $VERSION);

# Crypt::OpenSSL::Bignum::CTX is a trivial wrapper around OpenSSL's BN_CTX
# (a reusable scratch space for BIGNUM operations). In PerlOnJava it has no
# state to track — BigInteger is immutable — so we expose a tiny stub that
# just satisfies callers that thread a CTX through their arithmetic calls.
package Crypt::OpenSSL::Bignum::CTX;

sub new { return bless {}, shift }
sub DESTROY { }

1;

__END__

=head1 NAME

Crypt::OpenSSL::Bignum - Arbitrary-precision integers, OpenSSL API flavour

=head1 DESCRIPTION

Minimal subset of the CPAN C<Crypt::OpenSSL::Bignum> API, backed by
C<java.math.BigInteger> on PerlOnJava. Provides the constructors, conversions
and arithmetic primitives used by C<Crypt::OpenSSL::RSA> and similar modules
for shuttling BIGNUM values across the XS boundary.

C<Crypt::OpenSSL::Bignum::CTX> is provided as an empty stub for API
compatibility; it has no state because C<java.math.BigInteger> is immutable
and does not need a reusable scratch context.

=cut

