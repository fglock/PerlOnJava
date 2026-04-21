#!/usr/bin/perl
# Phase 2b regression: end-to-end in-memory TLS handshake between a
# client and server SSL handle, using a real PEM cert/key fixture.

use strict;
use warnings;
use Test::More;
use Net::SSLeay;

Net::SSLeay::load_error_strings();
Net::SSLeay::library_init();

my $key_pem  = "src/test/resources/module/Net-SSLeay/t/data/simple-cert.key.pem";
my $cert_pem = "src/test/resources/module/Net-SSLeay/t/data/simple-cert.cert.pem";

plan skip_all => "cert fixture missing" unless -f $key_pem && -f $cert_pem;

my $cctx = Net::SSLeay::CTX_new();
my $sctx = Net::SSLeay::CTX_new();
ok($cctx && $sctx, 'CTX_new for both sides');

# SSL_FILETYPE_PEM = 1
is(Net::SSLeay::CTX_use_PrivateKey_file($sctx, $key_pem, 1), 1,
   'CTX_use_PrivateKey_file succeeds');
is(Net::SSLeay::CTX_use_certificate_file($sctx, $cert_pem, 1), 1,
   'CTX_use_certificate_file succeeds');

# Client trusts anything (self-signed test cert). SslCtxState starts
# with verifyMode=0 so the TrustManager is accept-all by default.

my $c = Net::SSLeay::new($cctx);
my $s = Net::SSLeay::new($sctx);

my $cr = Net::SSLeay::BIO_new(Net::SSLeay::BIO_s_mem());
my $cw = Net::SSLeay::BIO_new(Net::SSLeay::BIO_s_mem());
my $sr = Net::SSLeay::BIO_new(Net::SSLeay::BIO_s_mem());
my $sw = Net::SSLeay::BIO_new(Net::SSLeay::BIO_s_mem());

Net::SSLeay::set_bio($c, $cr, $cw);
Net::SSLeay::set_bio($s, $sr, $sw);

Net::SSLeay::set_connect_state($c);
Net::SSLeay::set_accept_state($s);

# Pump: move cw → sr and sw → cr, calling do_handshake on both,
# up to 50 rounds.
my $done = 0;
for my $round (1 .. 50) {
    Net::SSLeay::do_handshake($c);
    my $cb = Net::SSLeay::BIO_pending($cw);
    if ($cb) {
        Net::SSLeay::BIO_write($sr, Net::SSLeay::BIO_read($cw, $cb));
    }

    Net::SSLeay::do_handshake($s);
    my $sb = Net::SSLeay::BIO_pending($sw);
    if ($sb) {
        Net::SSLeay::BIO_write($cr, Net::SSLeay::BIO_read($sw, $sb));
    }

    my $cok = Net::SSLeay::do_handshake($c);
    my $sok = Net::SSLeay::do_handshake($s);
    if ($cok > 0 && $sok > 0) { $done = $round; last; }
}

ok($done, "handshake completed in $done pump rounds");

SKIP: {
    skip "handshake didn't complete", 5 unless $done;

    is(Net::SSLeay::state($c), 3, 'client state = SSL_ST_OK');
    is(Net::SSLeay::state($s), 3, 'server state = SSL_ST_OK');

    my $proto = Net::SSLeay::get_version($c);
    like($proto, qr/^TLS/, "negotiated $proto");

    # Plaintext exchange
    my $msg = "ping from client";
    Net::SSLeay::write($c, $msg);
    my $cbytes = Net::SSLeay::BIO_pending($cw);
    Net::SSLeay::BIO_write($sr, Net::SSLeay::BIO_read($cw, $cbytes));
    my $heard = Net::SSLeay::read($s);
    is($heard, $msg, 'server reads client plaintext verbatim');

    my $reply = "pong from server";
    Net::SSLeay::write($s, $reply);
    my $sbytes = Net::SSLeay::BIO_pending($sw);
    Net::SSLeay::BIO_write($cr, Net::SSLeay::BIO_read($sw, $sbytes));
    my $got = Net::SSLeay::read($c);
    is($got, $reply, 'client reads server plaintext verbatim');
}

Net::SSLeay::free($c);
Net::SSLeay::free($s);
Net::SSLeay::CTX_free($cctx);
Net::SSLeay::CTX_free($sctx);

done_testing();
