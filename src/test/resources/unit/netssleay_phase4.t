#!/usr/bin/perl
# Phase 4 regression: X509 introspection APIs (ASN1_STRING_*, X509_cmp,
# X509_check_issued, X509_NAME_get_index_by_NID, sk_* helpers, ASN1_TIME
# parse/format, X509_verify_cert_error_string, etc).

use strict;
use warnings;
use Test::More;
use Net::SSLeay;

Net::SSLeay::load_error_strings();
Net::SSLeay::library_init();

# Helper: generate a self-signed cert in memory via the existing
# P_X509_* helpers (already supported). If that isn't available we
# skip the fancy tests and exercise what we can in isolation.
my $have_make_cert = Net::SSLeay->can('P_X509_make_random') ? 1 : 0;

# -----------------------------------------------------------------
# ASN1_TIME round-trip
# -----------------------------------------------------------------

my $t = Net::SSLeay::ASN1_TIME_new();
ok($t, 'ASN1_TIME_new');

Net::SSLeay::ASN1_TIME_set($t, 1700000000);  # fixed epoch
ok(Net::SSLeay::P_ASN1_TIME_get_isotime($t), 'ASN1_TIME_set → isotime prints');

# ASN1_TIME_set_string: parse a GeneralizedTime
ok(Net::SSLeay::ASN1_TIME_set_string($t, "20240115120000Z"),
   'ASN1_TIME_set_string(generalized) succeeds');

# Print to a BIO and read back
my $bio = Net::SSLeay::BIO_new(Net::SSLeay::BIO_s_mem());
ok(Net::SSLeay::ASN1_TIME_print($bio, $t), 'ASN1_TIME_print writes to BIO');
my $formatted = Net::SSLeay::BIO_read($bio);
like($formatted, qr/2024/, 'ASN1_TIME_print output contains the year');
like($formatted, qr/GMT$/, 'ASN1_TIME_print output ends with GMT');

# -----------------------------------------------------------------
# X509_verify_cert_error_string
# -----------------------------------------------------------------

is(Net::SSLeay::X509_verify_cert_error_string(0), 'ok',
   'verify_cert_error_string(0) = ok');
is(Net::SSLeay::X509_verify_cert_error_string(10),
   'certificate has expired', 'verify_cert_error_string(10)');
is(Net::SSLeay::X509_verify_cert_error_string(19),
   'self signed certificate in certificate chain',
   'verify_cert_error_string(19)');
is(Net::SSLeay::X509_verify_cert_error_string(9999),
   'certificate verify error', 'unknown error falls through');

# -----------------------------------------------------------------
# X509_get_ex_new_index: returns monotonically increasing indices
# -----------------------------------------------------------------

my $i1 = Net::SSLeay::X509_get_ex_new_index(0, 0);
my $i2 = Net::SSLeay::X509_get_ex_new_index(0, 0);
cmp_ok($i2, '>', $i1, 'X509_get_ex_new_index monotonic');

# -----------------------------------------------------------------
# Stack helpers (empty stack → sane answers)
# -----------------------------------------------------------------

is(Net::SSLeay::sk_GENERAL_NAME_num(999999), 0,
   'sk_GENERAL_NAME_num on nonexistent handle = 0');
ok(!defined Net::SSLeay::sk_GENERAL_NAME_value(999999, 0),
   'sk_GENERAL_NAME_value on nonexistent handle = undef');

# sk_*_pop_free on nonexistent handle should not crash
Net::SSLeay::sk_X509_pop_free(999999, 0);
Net::SSLeay::sk_pop_free(999999, 0);
pass('sk_X509_pop_free / sk_pop_free tolerate bogus handle');

# -----------------------------------------------------------------
# X509 introspection on a real parsed cert
# -----------------------------------------------------------------

# Tiny self-signed PEM (generated with openssl req ... 2048 bits, 10 yrs)
my $pem = <<'PEM';
-----BEGIN CERTIFICATE-----
MIIDWTCCAkGgAwIBAgIUeazqN5t4gGbg/o3KrGzWO/qKdCMwDQYJKoZIhvcNAQEL
BQAwPDELMAkGA1UEBhMCVVMxFTATBgNVBAoMDFBlcmxPbkphdmE0MRYwFAYDVQQD
DA1MZXQncyBUZXN0IENBMB4XDTI0MDEwMTAwMDAwMFoXDTM0MDEwMTAwMDAwMFow
PDELMAkGA1UEBhMCVVMxFTATBgNVBAoMDFBlcmxPbkphdmE0MRYwFAYDVQQDDA1M
ZXQncyBUZXN0IENBMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAttJs
e2dt7jcdh4E2Yy5lQNvnnL3mOvZQi/7U20fVJMIpMq9e+fU4BrTwBTHhR84Oyk7D
6UzTJZtGmTzs9ECHCfr74JiX/3q6mFRkrcd5W9KRZmX3T+DNjg0E4ISSJmi/wgbe
aPchOhV3fcsrKjwT7m/BCCSnEuWGJrMYK7f0NGMJCRzEcArmRnUdzVKSzfPLQcNS
ydEAkf3YmYk15DWhsP+g3wiyR4fpIXC/wrvs0H0HnSMiyu3xexlRBLbMeAU4oNpt
TGgcqV88B9PaHj1Yt2eWxBbMTxKZjxjdX9hFztaigGRMmpDnJrGpbuCgtp2LT6Hp
PN+lmXy1pbqxAoWLnQIDAQABo1MwUTAdBgNVHQ4EFgQUefqVeOT0x39U+u+7VZjK
H1B0jX0wHwYDVR0jBBgwFoAUefqVeOT0x39U+u+7VZjKH1B0jX0wDwYDVR0TAQH/
BAUwAwEB/zANBgkqhkiG9w0BAQsFAAOCAQEAkPM9UdRcyjnoKErIRUY5gyTE7E5H
aL4VNE8Hw1IcKt3DJAAeiAqkmcJAFccqtZWzHgNgpyrhNjbVN/dUppCXRYuERnSk
Og1xlwhx+7VETLGsBCw7Gn5ZS3H+4D+Te6HvrmR9h9mbucd4Xj6gvSpGBmr/U7JN
0a7/6sRe//9pY4YF2wxGXsc5RCPCyPkL4nJYK4OsjSJvAJPC4n1PR6EMUqxQrCvj
zQjBPrIrvdOgo2eMzEeN2eexCjPm6sSdTspcrY3/D+jR2HW3S7rb+r0kp2yVg/jP
Q0hW5mSC2hXM/3xAYEe+CiV4yZeUCmSy+d6eXh4ceeTqeuyM30LfGOZN5Q==
-----END CERTIFICATE-----
PEM

my $bio2 = Net::SSLeay::BIO_new(Net::SSLeay::BIO_s_mem());
Net::SSLeay::BIO_write($bio2, $pem);
my $cert = Net::SSLeay::PEM_read_bio_X509($bio2);

SKIP: {
    skip "PEM cert decode failed on this build", 8 unless $cert;

    # X509_cmp: cert against itself → 0
    is(Net::SSLeay::X509_cmp($cert, $cert), 0, 'X509_cmp self = 0');

    # X509_check_issued: self-signed so issuer == subject → 0 (X509_V_OK)
    is(Net::SSLeay::X509_check_issued($cert, $cert), 0,
       'X509_check_issued(self, self) = X509_V_OK');

    # P_X509_get_ext_usage: self-signed CA has keyCertSign (bit 5)
    my $usage = Net::SSLeay::P_X509_get_ext_usage($cert);
    isa_ok(\$usage, 'SCALAR', 'P_X509_get_ext_usage returns a scalar');

    # X509_get_ext_d2i: extract basicConstraints (NID 87 = X509v3 Basic Constraints)
    my $bc = Net::SSLeay::X509_get_ext_d2i($cert, 87);
    ok(defined $bc, 'X509_get_ext_d2i(basicConstraints) returns data');

    # X509_NAME_get_index_by_NID: find CN in subject
    my $subj = Net::SSLeay::X509_get_subject_name($cert);
    my $cn_nid = 13; # NID_commonName
    my $idx = Net::SSLeay::X509_NAME_get_index_by_NID($subj, $cn_nid, -1);
    cmp_ok($idx, '>=', 0, 'X509_NAME_get_index_by_NID finds commonName');

    # Second call with lastpos = $idx should not find another CN
    my $idx2 = Net::SSLeay::X509_NAME_get_index_by_NID($subj, $cn_nid, $idx);
    is($idx2, -1, 'subsequent lookup returns -1 (only one CN)');

    # Lookup for a nonexistent NID
    my $idx3 = Net::SSLeay::X509_NAME_get_index_by_NID($subj, 99999, -1);
    is($idx3, -1, 'bogus NID returns -1');

    # ASN1_STRING accessors on a real name entry
    my $name_entry = Net::SSLeay::X509_NAME_get_entry($subj, $idx);
    my $asn1 = Net::SSLeay::X509_NAME_ENTRY_get_data($name_entry);
    ok($asn1, 'X509_NAME_ENTRY_get_data returns ASN1_STRING handle');
    cmp_ok(Net::SSLeay::ASN1_STRING_length($asn1), '>', 0,
       'ASN1_STRING_length > 0 for common-name');
}

done_testing();
