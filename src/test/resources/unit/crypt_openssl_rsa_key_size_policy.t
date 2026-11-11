use strict;
use warnings;
use Config;
use Test::More;

BEGIN {
    $Config{archname} =~ /^java-/
        or plan skip_all => 'RSA key-size policy is specific to the PerlOnJava adapter';

    my $loaded = eval { require Crypt::OpenSSL::RSA; 1 };
    if (!$loaded && $Config{archname} =~ /^java-/) {
        $loaded = eval q{
            package Crypt::OpenSSL::RSA;
            our $VERSION = '0.41';
            require XSLoader;
            XSLoader::load('Crypt::OpenSSL::RSA', $VERSION);
            1;
        };
    }
    $loaded
        or plan skip_all => 'Crypt::OpenSSL::RSA required';
}

plan tests => 14;

sub rejects_key_size {
    my ($bits, $label) = @_;
    my $generated = eval { Crypt::OpenSSL::RSA->generate_key($bits); 1 };
    ok(!$generated, "$label is rejected before key generation");
    like($@, qr/RSA key size must be an integer from 1024 through 8192 bits/,
        "$label reports the accepted RSA key-size range");
}

rejects_key_size(0, 'zero bits');
rejects_key_size(512, 'undersized RSA key');
rejects_key_size(1025, 'non-byte-aligned RSA key');
rejects_key_size(8193, 'oversized RSA key');
rejects_key_size(2048.5, 'fractional RSA key size');
rejects_key_size('not-a-number', 'nonnumeric RSA key size');

my $rsa = Crypt::OpenSSL::RSA->generate_key(2048);
ok($rsa, 'the NIST minimum RSA key size can be generated');
my $message = 'RSA key-size policy regression';
my $signature = $rsa->sign($message);
ok($rsa->verify($message, $signature),
    'a key at the secure minimum can sign and verify');
