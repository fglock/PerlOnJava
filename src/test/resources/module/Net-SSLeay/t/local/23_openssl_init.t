use lib 'inc';

use Net::SSLeay;
use Test::Net::SSLeay qw(data_file_path initialise_libssl);

# We don't do intialise_libssl() now because want to test non-default
# initialisation.
#
#initialise_libssl(); # Don't do this

if (defined &Net::SSLeay::OPENSSL_INIT_set_config_filename) {
    plan(tests => 10);
} else {
    plan(skip_all => 'No OPENSSL_INIT_set_config_filename()');
}

# Supplied OpenSSL configuration file may load unwanted providers.
delete $ENV{OPENSSL_CONF};

# Test that our test specific OpenSSL configuration file loads
# correctly.
#
# We then check that we get our special settings back with the OpenSSL
# API functions. The default OpenSSL configuration file would give
# different results from what we expect from our test specific OpenSSL
# configuration.
{
    my $filename = data_file_path('openssl_init_test.conf');
    my $settings = Net::SSLeay::OPENSSL_INIT_new();
    ok($settings, 'OPENSSL_INIT_new');

    my $ret = Net::SSLeay::OPENSSL_INIT_set_config_filename($settings, $filename);
    is($ret, 1, 'OPENSSL_INIT_set_config_filename');

    $ret = Net::SSLeay::OPENSSL_INIT_set_config_appname($settings, 'openssl_conf');
    is($ret, 1, 'OPENSSL_INIT_set_config_appname');

    # Defaults for config file loading for libssl and libcrypto differ
    # between OpenSSL versions. Calling libssl init also calls
    # libcrypto init. Therefore we do the initialisation in this order
    # and with the flag that ensure the configuration is always
    # loaded.
    my $crypto_init_flags = Net::SSLeay::OPENSSL_INIT_LOAD_CONFIG();
    $ret = Net::SSLeay::OPENSSL_init_crypto($crypto_init_flags, $settings);
    is($ret, 1, 'OPENSSL_INIT_init_crypto');
    $ret = Net::SSLeay::OPENSSL_init_ssl($crypto_init_flags, $settings);
    is($ret, 1, 'OPENSSL_INIT_init_ssl');

    Net::SSLeay::OPENSSL_INIT_free($settings);

    # Now see that the values we get back from SSL_CTX and SSL reflect
    # the values in the configuration file that was just loaded.
    my $ctx = Net::SSLeay::CTX_new_with_method(Net::SSLeay::TLS_client_method());
    my $ssl = Net::SSLeay::new($ctx);
    is(Net::SSLeay::CTX_get_min_proto_version($ctx), Net::SSLeay::TLS1_3_VERSION(), 'conf: MinProtocol set');
    is(Net::SSLeay::CTX_get_max_proto_version($ctx), 0, 'conf: MaxProtocol unset');
    is(Net::SSLeay::get_cipher_list($ssl, 0), 'TLS_AES_128_CCM_8_SHA256', 'conf: 1st cipher TLS_AES_128_CCM_8_SHA256');
    is(Net::SSLeay::get_cipher_list($ssl, 1), 'AES256-GCM-SHA384', 'conf: 2nd cipher AES256-GCM-SHA384');
    is(Net::SSLeay::get_cipher_list($ssl, 2), undef, 'conf: 3rd cipher is undefined');
}
