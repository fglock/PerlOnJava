package Net::SSLeay;

# PerlOnJava stub for Net::SSLeay.
# The actual implementation is in:
#   src/main/java/org/perlonjava/runtime/perlmodule/NetSSLeay.java
#
# This replaces the CPAN XS version to avoid:
#   - autosplit.ix loading failures
#   - AUTOLOAD infinite recursion (constant() is undefined without XS)
#   - StackOverflowError crashes

use strict;
use warnings;
use Exporter 'import';

our $VERSION = '1.96';

# Load the Java XS implementation (provides constants + no-op inits)
XSLoader::load('Net::SSLeay', $VERSION);

our @EXPORT_OK = qw(
    constant
    library_init load_error_strings SSLeay_add_ssl_algorithms
    OpenSSL_add_all_digests randomize
    SSLeay SSLeay_version OPENSSL_VERSION_NUMBER
    ERR_clear_error ERR_get_error ERR_error_string print_errs

    ERROR_NONE ERROR_SSL ERROR_WANT_READ ERROR_WANT_WRITE
    ERROR_WANT_X509_LOOKUP ERROR_SYSCALL ERROR_ZERO_RETURN
    ERROR_WANT_CONNECT ERROR_WANT_ACCEPT

    VERIFY_NONE VERIFY_PEER VERIFY_FAIL_IF_NO_PEER_CERT VERIFY_CLIENT_ONCE
    FILETYPE_PEM FILETYPE_ASN1

    OP_ALL OP_SINGLE_DH_USE OP_SINGLE_ECDH_USE
    OP_NO_SSLv2 OP_NO_SSLv3 OP_NO_TLSv1 OP_NO_TLSv1_1 OP_NO_TLSv1_2 OP_NO_TLSv1_3
    OP_CIPHER_SERVER_PREFERENCE OP_NO_COMPRESSION

    MODE_ENABLE_PARTIAL_WRITE MODE_ACCEPT_MOVING_WRITE_BUFFER MODE_AUTO_RETRY

    X509_V_FLAG_TRUSTED_FIRST X509_V_FLAG_PARTIAL_CHAIN X509_V_FLAG_CRL_CHECK
    TLSEXT_STATUSTYPE_ocsp OCSP_RESPONSE_STATUS_SUCCESSFUL V_OCSP_CERTSTATUS_GOOD

    TLS1_VERSION TLS1_1_VERSION TLS1_2_VERSION TLS1_3_VERSION
    SESS_CACHE_CLIENT SESS_CACHE_SERVER SESS_CACHE_BOTH SESS_CACHE_OFF
    NID_commonName NID_subject_alt_name
    SSL_SENT_SHUTDOWN SSL_RECEIVED_SHUTDOWN
    LIBRESSL_VERSION_NUMBER
);

our %EXPORT_TAGS = (
    all => \@EXPORT_OK,
);

# Variables that IO::Socket::SSL accesses
our $trace = 0;

# ---- Pure Perl utility functions (stubs) ----
# These are defined in the real Net::SSLeay as autoloaded Perl functions.
# We provide minimal stubs so they're "autoloadable" (findable), even though
# they require OpenSSL functionality we don't have.

sub die_if_ssl_error { die "SSL error (PerlOnJava stub)" if $_[0] }
sub die_now          { die $_[0] || "Died" }

sub do_https   { _not_implemented("do_https") }
sub get_http   { _not_implemented("get_http") }
sub get_http4  { _not_implemented("get_http4") }
sub get_https  { _not_implemented("get_https") }
sub get_https3 { _not_implemented("get_https3") }
sub get_https4 { _not_implemented("get_https4") }
sub get_httpx  { _not_implemented("get_httpx") }
sub get_httpx4 { _not_implemented("get_httpx4") }
sub post_http  { _not_implemented("post_http") }
sub post_http4 { _not_implemented("post_http4") }
sub post_https { _not_implemented("post_https") }
sub post_https3 { _not_implemented("post_https3") }
sub post_https4 { _not_implemented("post_https4") }
sub post_httpx  { _not_implemented("post_httpx") }
sub post_httpx4 { _not_implemented("post_httpx4") }
sub sslcat      { _not_implemented("sslcat") }
sub tcpcat      { _not_implemented("tcpcat") }
sub tcpxcat     { _not_implemented("tcpxcat") }

sub dump_peer_certificate      { _not_implemented("dump_peer_certificate") }
sub set_cert_and_key           { _not_implemented("set_cert_and_key") }
sub set_server_cert_and_key    { _not_implemented("set_server_cert_and_key") }

sub make_form {
    my @pairs;
    while (@_) {
        my ($k, $v) = (shift, shift);
        push @pairs, "$k=" . _url_encode($v // '');
    }
    return join('&', @pairs);
}

sub make_headers {
    my @h;
    while (@_) {
        my ($k, $v) = (shift, shift);
        push @h, "$k: $v\r\n";
    }
    return join('', @h) . "\r\n";
}

sub _url_encode {
    my $s = shift;
    $s =~ s/([^A-Za-z0-9\-_.~])/sprintf("%%%02X", ord($1))/ge;
    return $s;
}

sub _not_implemented {
    die "Net::SSLeay::$_[0] is not implemented in PerlOnJava (no OpenSSL backend)\n";
}

# AUTOLOAD mimics the real Net::SSLeay AUTOLOAD behavior:
#   - constant() succeeds ($! == 0): cache the constant as a sub
#   - constant() fails with EINVAL: not a constant name, try AutoLoader for .al files
#   - constant() fails with other errno (ENOENT): known-but-unavailable OpenSSL macro
sub AUTOLOAD {
    my $constname;
    our $AUTOLOAD;
    ($constname = $AUTOLOAD) =~ s/.*:://;
    return if $constname eq 'DESTROY';

    my $val = constant($constname);
    if ($! != 0) {
        if ($! =~ /((Invalid)|(not valid))/i || $!{EINVAL}) {
            # Not a constant — fall through to AutoLoader for .al file lookup
            require AutoLoader;
            $AutoLoader::AUTOLOAD = $AUTOLOAD;
            goto &AutoLoader::AUTOLOAD;
        }
        else {
            require Carp;
            Carp::croak("Your vendor has not defined SSLeay macro $constname");
        }
    }
    # Successfully resolved constant — install as a sub for future calls
    no strict 'refs';
    eval "sub $AUTOLOAD { $val }";
    goto &$AUTOLOAD;
}

1;

__END__

=head1 NAME

Net::SSLeay - PerlOnJava stub providing SSL constants

=head1 DESCRIPTION

This is a minimal stub of Net::SSLeay for PerlOnJava.  It provides the
constants and version information that IO::Socket::SSL needs, but does
not implement the full OpenSSL C API bindings.

Actual SSL/TLS operations in PerlOnJava are handled by the Java-backed
IO::Socket::SSL implementation using C<javax.net.ssl>.

=cut
