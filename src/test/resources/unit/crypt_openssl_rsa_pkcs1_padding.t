use strict;
use warnings;
use Config;
use Test::More;

BEGIN {
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

plan tests => 1;

my $rsa = Crypt::OpenSSL::RSA->generate_key(1024);
$rsa->use_pkcs1_padding;
my $signature = $rsa->sign('PKCS#1 selector regression');

ok(
    $rsa->verify('PKCS#1 selector regression', $signature),
    'PKCS#1 v1.5 remains selectable for signature operations',
);
