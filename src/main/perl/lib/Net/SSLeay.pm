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

# AUTOLOAD for any remaining constant lookups.
# Uses the Java-backed constant() function which returns 0 + sets $! = EINVAL
# for unknown names, preventing infinite recursion.
sub AUTOLOAD {
    my $constname;
    our $AUTOLOAD;
    ($constname = $AUTOLOAD) =~ s/.*:://;
    return if $constname eq 'DESTROY';

    my $val = constant($constname);
    if ($! == 0) {
        # Successfully resolved constant — install as a sub for future calls
        no strict 'refs';
        *{$AUTOLOAD} = sub { $val };
        goto &$AUTOLOAD;
    }
    require Carp;
    Carp::croak("Net::SSLeay constant '$constname' not defined (PerlOnJava stub)");
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
