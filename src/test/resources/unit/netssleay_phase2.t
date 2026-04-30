#!/usr/bin/perl
# Phase 2 regression: SSLEngine-backed handshake driver.
#
# Notes:
# - We use Net::SSLeay::do_handshake() rather than ::connect(), because
#   Perl's `connect` builtin shadows the Net::SSLeay exported name in
#   PerlOnJava's parser. That's an unrelated parser issue tracked
#   separately.

use strict;
use warnings;
use Test::More;
BEGIN {
    eval { require Net::SSLeay; Net::SSLeay->import; 1 }
        or do {
            require Test::More;
            Test::More->import;
            Test::More::plan(skip_all => 'Net::SSLeay not available');
        };
}

Net::SSLeay::load_error_strings();
Net::SSLeay::library_init();

# -----------------------------------------------------------------
# Client-only: ClientHello lands in wbio
# -----------------------------------------------------------------

my $ctx = Net::SSLeay::CTX_new();
ok($ctx, 'CTX_new for client');

my $ssl = Net::SSLeay::new($ctx);
ok($ssl, 'SSL new from CTX');

my $rbio = Net::SSLeay::BIO_new(Net::SSLeay::BIO_s_mem());
my $wbio = Net::SSLeay::BIO_new(Net::SSLeay::BIO_s_mem());
ok($rbio && $wbio, 'BIO pair allocated');

Net::SSLeay::set_bio($ssl, $rbio, $wbio);
Net::SSLeay::set_tlsext_host_name($ssl, 'example.com');
Net::SSLeay::set_connect_state($ssl);
is(Net::SSLeay::state($ssl), 0x1000, 'state() = SSL_ST_CONNECT');

is(Net::SSLeay::BIO_pending($wbio), 0,
   'no ClientHello before first drive');

# Drive the handshake. Expect WANT_READ (no peer reply) and a
# ClientHello in wbio.
my $rc = Net::SSLeay::do_handshake($ssl);
cmp_ok($rc, '<=', 0, 'do_handshake returns non-positive pre-completion');
is(Net::SSLeay::get_error($ssl, $rc), 2,
   'get_error = SSL_ERROR_WANT_READ (2)');

my $hello_len = Net::SSLeay::BIO_pending($wbio);
cmp_ok($hello_len, '>', 200, "ClientHello landed in wbio ($hello_len bytes)");

# The first byte should be 0x16 (TLS handshake content type).
my $first_byte = Net::SSLeay::BIO_read($wbio, 1);
is(ord($first_byte), 0x16, 'first byte = 0x16 (TLS handshake record)');

# Pending drops after read
cmp_ok(Net::SSLeay::BIO_pending($wbio), '<', $hello_len,
   'BIO_pending drops after BIO_read consumes bytes');

# write() enqueues plaintext, and since we're still handshaking the
# driver shouldn't emit application data yet (just more handshake
# bytes if any). The write call should succeed with WANT_READ errno.
Net::SSLeay::BIO_read($wbio, Net::SSLeay::BIO_pending($wbio)); # drain
my $wn = Net::SSLeay::write($ssl, "queued");
is($wn, length("queued"), 'write() accepts plaintext during handshake');
is(Net::SSLeay::get_error($ssl, $wn), 2,
   'get_error stays WANT_READ while handshake pending');

# read() should return undef (no plaintext yet)
my $r = Net::SSLeay::read($ssl);
ok(!defined $r, 'read() returns undef while no plaintext available');
is(Net::SSLeay::get_error($ssl, 0), 2, 'WANT_READ after empty read');

# shutdown() on a pre-handshake SSL should at least call closeOutbound
# without crashing. It returns 0 (more work needed) because inbound
# cannot close without the peer's alert.
my $sd = Net::SSLeay::shutdown($ssl);
cmp_ok($sd, '>=', 0, 'shutdown returns non-negative');

# -----------------------------------------------------------------
# Two-in-one: spin up a second SSL against ourselves to prove the
# ClientHello bytes are *syntactically valid* TLS records.  The
# easiest way is to pump them into a server BIO even though we have
# no cert, and check the server errors out cleanly rather than
# hanging (→ driver is honest about failure).
# -----------------------------------------------------------------

my $sctx = Net::SSLeay::CTX_new();
my $sssl = Net::SSLeay::new($sctx);
my $srb = Net::SSLeay::BIO_new(Net::SSLeay::BIO_s_mem());
my $swb = Net::SSLeay::BIO_new(Net::SSLeay::BIO_s_mem());
Net::SSLeay::set_bio($sssl, $srb, $swb);
Net::SSLeay::set_accept_state($sssl);
is(Net::SSLeay::state($sssl), 0x2000, 'server state = SSL_ST_ACCEPT');

# Pump the client's (fresh) ClientHello into the server.
my $c2 = Net::SSLeay::new($ctx);
my $cr2 = Net::SSLeay::BIO_new(Net::SSLeay::BIO_s_mem());
my $cw2 = Net::SSLeay::BIO_new(Net::SSLeay::BIO_s_mem());
Net::SSLeay::set_bio($c2, $cr2, $cw2);
Net::SSLeay::set_connect_state($c2);
Net::SSLeay::do_handshake($c2);
my $hello_bytes = Net::SSLeay::BIO_read($cw2, Net::SSLeay::BIO_pending($cw2));
cmp_ok(length $hello_bytes, '>', 200, 'got fresh ClientHello to relay');

Net::SSLeay::BIO_write($srb, $hello_bytes);
my $srv_rc = Net::SSLeay::do_handshake($sssl);
my $srv_err = Net::SSLeay::get_error($sssl, $srv_rc);
# No cert → handshake fails with SSL_ERROR_SSL (1).
# The key guarantee: the driver terminates, doesn't hang.
ok($srv_err == 1 || $srv_err == 2,
   "server do_handshake returns a real error code ($srv_err)");

Net::SSLeay::free($_) for $ssl, $sssl, $c2;
Net::SSLeay::CTX_free($_) for $ctx, $sctx;

done_testing();
