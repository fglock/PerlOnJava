use strict;
use warnings;

use Net::SSLeay;
use Test::More tests => 20;

Net::SSLeay::load_error_strings();
Net::SSLeay::library_init();

my $ctx = Net::SSLeay::CTX_new_with_method(Net::SSLeay::TLS_method());
my $ssl = $ctx && Net::SSLeay::new($ctx);
ok($ctx && $ssl, 'created an SSL context and handle');

my @states;
if ($ssl) {
    Net::SSLeay::set_fd($ssl, -1);
    Net::SSLeay::CTX_set_info_callback($ctx, sub { push @states, $_[1] });
    Net::SSLeay::connect($ssl);
}
ok(@states, 'CTX info callback observes a connection state transition');

Net::SSLeay::free($ssl) if $ssl;
Net::SSLeay::CTX_free($ctx) if $ctx;

my $ec = Net::SSLeay::EC_KEY_generate_key('prime256v1');
ok($ec, 'generated an EC key');

my $pkey = Net::SSLeay::EVP_PKEY_new();
ok($pkey, 'allocated an empty EVP_PKEY');

my $assigned = $pkey && $ec
    ? Net::SSLeay::EVP_PKEY_assign_EC_KEY($pkey, $ec)
    : 0;
ok($assigned, 'assigned the EC key to the empty EVP_PKEY');

my $pem = $assigned ? Net::SSLeay::PEM_get_string_PrivateKey($pkey) : '';
like($pem, qr/PRIVATE KEY/, 'assigned EVP_PKEY serializes as a private key');

Net::SSLeay::EVP_PKEY_free($pkey) if $pkey;

my $cert_ctx = Net::SSLeay::CTX_new();
my $cert = 'src/test/resources/module/Net-SSLeay/t/data/simple-cert.cert.pem';
my $key  = 'src/test/resources/module/Net-SSLeay/t/data/simple-cert.key.pem';
ok(Net::SSLeay::set_cert_and_key($cert_ctx, $cert, $key),
   'set_cert_and_key succeeds in scalar context');
my ($unused, $cert_errors) =
    Net::SSLeay::set_cert_and_key($cert_ctx, $cert, $key);
is($cert_errors, '', 'set_cert_and_key returns an empty error string in list context');
Net::SSLeay::CTX_free($cert_ctx);

my $cipher_ctx = Net::SSLeay::CTX_new();
my $cipher_ssl = Net::SSLeay::new($cipher_ctx);
SKIP: {
    skip 'Net::SSLeay build does not expose get_ciphers', 12
        unless defined &Net::SSLeay::get_ciphers;
    my @ciphers = Net::SSLeay::get_ciphers($cipher_ssl);
    cmp_ok(scalar @ciphers, '>=', 10, 'get_ciphers returns the supported cipher handles');
    my $cipher_name = Net::SSLeay::CIPHER_get_name($ciphers[0]);
    ok(length($cipher_name) >= 7, 'cipher handle has a descriptive name');
    my $algorithm_bits;
    my $cipher_bits = Net::SSLeay::CIPHER_get_bits($ciphers[0], $algorithm_bits);
    ok($cipher_bits > 0 && $algorithm_bits >= $cipher_bits,
       'cipher bits and algorithm bits are populated');
    is(Net::SSLeay::get_cipher_list($cipher_ssl, 0), $cipher_name,
       'get_cipher_list agrees with the first cipher handle');

    my %cipher_by_name = map {
        Net::SSLeay::CIPHER_get_name($_) => $_
    } @ciphers;
    for my $case (
        [ 'TLS_AES_128_GCM_SHA256', 'TLSv1.3',
          qr/TLSv1\.3 Kx=any Au=any Enc=AESGCM\(128\) Mac=AEAD/ ],
        [ 'ECDHE-RSA-AES128-GCM-SHA256', 'TLSv1.2',
          qr/TLSv1\.2 Kx=ECDH Au=RSA Enc=AESGCM\(128\) Mac=AEAD/ ],
    ) {
        my ($name, $protocol, $description) = @$case;
        my $handle = $cipher_by_name{$name};
        ok($handle, "$name has an SSL_CIPHER handle");
        is(Net::SSLeay::CIPHER_get_version($handle), $protocol,
           "$name reports $protocol");
        my $algorithm_bits;
        my $strength_bits = Net::SSLeay::CIPHER_get_bits($handle, $algorithm_bits);
        is_deeply([$strength_bits, $algorithm_bits], [128, 128],
                  "$name reports 128-bit strength and algorithm size");
        like(&Net::SSLeay::CIPHER_description($handle), $description,
             "$name description reports its protocol and algorithms");
    }
}
Net::SSLeay::free($cipher_ssl);
Net::SSLeay::CTX_free($cipher_ctx);
