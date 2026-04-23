#!/usr/bin/perl
# Phase 3 + 7 regression: PKCS12_parse / PKCS12_newpass / session
# serialization, and the OCSP API surface. We only check that the
# entry points are callable and return sensible values; end-to-end
# PKCS12 is covered by P_PKCS12_load_file in other tests.

use strict;
use warnings;
use Test::More;
use Net::SSLeay;

Net::SSLeay::load_error_strings();
Net::SSLeay::library_init();

# PKCS12_parse on an empty BIO → empty list
my $bio = Net::SSLeay::BIO_new(Net::SSLeay::BIO_s_mem());
my @out = Net::SSLeay::PKCS12_parse($bio, "password");
is(scalar @out, 0, 'PKCS12_parse on empty BIO returns empty list');

# PKCS12_parse on garbage → empty list
my $bio2 = Net::SSLeay::BIO_new(Net::SSLeay::BIO_s_mem());
Net::SSLeay::BIO_write($bio2, "not a pkcs12 blob");
my @out2 = Net::SSLeay::PKCS12_parse($bio2, "");
is(scalar @out2, 0, 'PKCS12_parse on garbage returns empty list');

# PKCS12_newpass: we can't safely implement without re-encoding
is(Net::SSLeay::PKCS12_newpass("whatever", "old", "new"), 0,
   'PKCS12_newpass returns 0 (honest failure)');

# i2d_SSL_SESSION / d2i_SSL_SESSION: round-trip opaque token
my $tok = Net::SSLeay::i2d_SSL_SESSION(0x12345);
ok(defined $tok && length $tok == 8, 'i2d_SSL_SESSION yields 8-byte token');
my $h = Net::SSLeay::d2i_SSL_SESSION($tok);
is($h, 0x12345, 'd2i_SSL_SESSION recovers the handle id');

# -----------------------------------------------------------------
# Phase 7: OCSP entry points callable, return sane shapes
# -----------------------------------------------------------------

my $req = Net::SSLeay::OCSP_REQUEST_new();
ok($req, 'OCSP_REQUEST_new returns handle');
Net::SSLeay::OCSP_REQUEST_free($req);
pass('OCSP_REQUEST_free tolerates handle');

is(Net::SSLeay::OCSP_response_status(0), 0,
   'OCSP_response_status returns 0 for empty response');
is(Net::SSLeay::OCSP_response_status_str(0), 'successful',
   'OCSP_response_status_str(0) = successful');
is(Net::SSLeay::OCSP_response_status_str(6), 'unauthorized',
   'OCSP_response_status_str(6) = unauthorized');
is(Net::SSLeay::OCSP_response_status_str(99), 'unknown',
   'OCSP_response_status_str(99) = unknown');

my @results = Net::SSLeay::OCSP_response_results();
is(scalar @results, 0, 'OCSP_response_results returns empty list');

ok(Net::SSLeay::OCSP_request_add1_nonce(),
   'OCSP_request_add1_nonce returns truthy');
ok(Net::SSLeay::OCSP_request_add0_id(0, 0),
   'OCSP_request_add0_id returns truthy');

done_testing();
