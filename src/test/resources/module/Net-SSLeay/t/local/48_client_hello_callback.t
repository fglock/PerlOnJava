use lib 'inc';

use Net::SSLeay;
use Test::Net::SSLeay qw(
    can_fork data_file_path initialise_libssl new_ctx tcp_socket
);

BEGIN {
    if (not defined &Net::SSLeay::CTX_set_client_hello_cb) {
        plan skip_all => "No SSL_CTX_set_client_hello_cb()";
    } elsif (not can_fork()) {
        plan skip_all => "fork() not supported on this system";
    } else {
        plan tests => 41;
    }
}

initialise_libssl();

my $server = tcp_socket();
my $pid;

my $cert_pem = data_file_path('simple-cert.cert.pem');
my $key_pem  = data_file_path('simple-cert.key.pem');

my $cb_test_arg = [1, 'string for hello cb test arg'];

# As of 2023-08, even the latest in-development OpenSSL allows
# connections with SSLv2 ClientHello. Tested with OpenSSL 0.9.8f as
# client and OpenSSL 3.2.0-dev from git master branch as
# server. Trigger alert 42 as a marker.
sub client_hello_cb_v2hello_detection
{
    my ($ssl, $arg) = @_;

    pass('client_hello_cb_v2hello_detection called for SSLv2 hello');
    is(Net::SSLeay::client_hello_isv2($ssl), 1, 'SSLv2 ClientHello');
    is(Net::SSLeay::client_hello_get0_legacy_version($ssl), 0x0301, 'SSLv2 get0_legacy_version');

    my $random  = Net::SSLeay::client_hello_get0_random($ssl);
    my $sess_id = Net::SSLeay::client_hello_get0_session_id($ssl);
    my $ciphers = Net::SSLeay::client_hello_get0_ciphers($ssl);
    my $compres = Net::SSLeay::client_hello_get0_compression_methods($ssl);
    is($random,  pack('H*', '1f90dda05ec4a857523dcc0ae06c461a99c36ce647a84aa64061c054333376b9'), 'SSLv2 get0_random / Challenge');
    is($sess_id, '', 'SSLv2 get0_session_id');
    is($ciphers, pack('H*', '00003900003800003500001600001300000a00003300003200002f0000070000050000040000150000120000090000ff'), 'SSLv2 get0_ciphers');
    is($compres, pack('H*', '00'), 'SSLv2 get0_compression_methods');

    # See bug https://github.com/openssl/openssl/pull/8756
    # With 1.1.1b and earlier, MALLOC_FAILURE is raised when there are
    # no extensions. This is fixed in 1.1.1c.
    my $extensions = Net::SSLeay::client_hello_get1_extensions_present($ssl);
    Net::SSLeay::SSLeay > 0x1010102f ? # 1.1.1c or later
	is_deeply($extensions, [], 'SSLv2 get1_extensions_present') :  # No extensions: empty array
	is($extensions, undef, 'SSLv2 get1_extensions_present buggy'); # No extensions: buggy undef

    if (defined &Net::SSLeay::client_hello_get_extension_order) {
	$extensions = Net::SSLeay::client_hello_get_extension_order($ssl);
	is_deeply($extensions, [], 'SSLv2 get_extension_order');
    } else {
      SKIP: { skip('Do not have Net::SSLeay::client_hello_get_extension_order', 1); }
    }

    my $al = Net::SSLeay::AD_BAD_CERTIFICATE();
    return (Net::SSLeay::CLIENT_HELLO_ERROR(), $al);
}

# See that the exact same reference with unchanged contents are made
# available for the callback. Allow handshake to proceed.
sub client_hello_cb_getters
{
    my ($ssl, $arg) = @_;

    pass('client_hello_cb_getters called for TLS hello');
    is(Net::SSLeay::client_hello_isv2($ssl), 0, 'Not SSLv2 ClientHello');
    is(Net::SSLeay::client_hello_get0_legacy_version($ssl), 0x0303, 'TLS get0_legacy_version');

    my $random  = Net::SSLeay::client_hello_get0_random($ssl);
    my $sess_id = Net::SSLeay::client_hello_get0_session_id($ssl);
    my $ciphers = Net::SSLeay::client_hello_get0_ciphers($ssl);
    my $compres = Net::SSLeay::client_hello_get0_compression_methods($ssl);
    is($random,  pack('H*', '8bbef485edd728d6c02c421b5a9a3a137d6dfda43c5796ef825d8ac7dcbbbc53'), 'TLS get0_random');
    is($sess_id, pack('H*', '0d687c7511cb0b65eb3cde414c2385bc0ecb56d8c81403c571184c4acbd1ee31'), 'TLS get0_session_id');
    is($ciphers, pack('H*', '130213031301c02cc03000a3009fcca9cca8ccaac0afc0adc0a3c09fc05dc061c057c05300a7c02bc02f00a2009ec0aec0acc0a2c09ec05cc060c056c05200a6c024c028006b006ac073c07700c400c3006d00c5c023c02700670040c072c07600be00bd006c00bfc00ac0140039003800880087c019003a0089c009c0130033003200450044c01800340046009dc0a1c09dc051009cc0a0c09cc050003d00c0003c00ba00350084002f004100ff'), 'TLS get0_ciphers');
    is($compres, pack('H*', '00'), 'TLS get0_compression_methods');

    # OpenSSL extensions_presents does not guarantee that extensions
    # are returned in the order the appear ClientHello. Therefore we
    # compare sorted arrays. Note: that the both functions also do not
    # return extensions OpenSSL does not recognise. For more, see:
    # https://github.com/openssl/openssl/issues/18286#issuecomment-1123436664
    my @ordered_ext = (11, 10, 35, 22, 23, 13, 43, 45, 51);
    my $extensions = Net::SSLeay::client_hello_get1_extensions_present($ssl);
    is_deeply($extensions, \@ordered_ext, 'TLS get1_extensions_present');

    if (defined &Net::SSLeay::client_hello_get_extension_order) {
	$extensions = Net::SSLeay::client_hello_get_extension_order($ssl);
	is_deeply($extensions, \@ordered_ext, 'TLS get_extension_order');
    } else {
      SKIP: { skip('Do not have Net::SSLeay::client_hello_get_extension_order', 1); }
    }

    my $ext_ems = Net::SSLeay::client_hello_get0_ext($ssl, Net::SSLeay::TLSEXT_TYPE_extended_master_secret());
    my $ext_ver = Net::SSLeay::client_hello_get0_ext($ssl, Net::SSLeay::TLSEXT_TYPE_supported_versions());
    my $ext_n_a = Net::SSLeay::client_hello_get0_ext($ssl, 101);
    is($ext_ems, '', 'TLS get0_ext extended master secret'); # Present with empty value
    is($ext_ver, pack('H*', '080304030303020301'), 'TLS get0_ext supported versions');
    is($ext_n_a, undef, 'TLS get0_ext extension not present'); # Not present

    my $al = Net::SSLeay::AD_HANDSHAKE_FAILURE();
    return (Net::SSLeay::CLIENT_HELLO_ERROR(), $al);
}

# See that the exact same reference with unchanged contents are made
# available for the callback. Allow handshake to proceed.
sub client_hello_cb_value_passing
{
    my ($ssl, $arg) = @_;

    pass('client_hello_cb_value_passing called');
    is($cb_test_arg, $$arg, 'callback arg passed correctly');
    is_deeply($cb_test_arg, $$arg, 'callback arg contents passed correctly');
    return Net::SSLeay::CLIENT_HELLO_SUCCESS();
}

# Abort handshake with an ALPN alert. Test this on the client side.
sub client_hello_cb_alert_alpn
{
    my ($ssl, $arg) = @_;

    pass('client_hello_cb_alert_alpn called');
    my $al = Net::SSLeay::AD_NO_APPLICATION_PROTOCOL();
    return (Net::SSLeay::CLIENT_HELLO_ERROR(), $al);
}

# Check that alert is ignored with success return. Allow handshake to
# proceed.
sub client_hello_cb_conflicting_return_value
{
    my ($ssl, $arg) = @_;

    pass('client_hello_cb_conflicting_return_value called');
    my $al = Net::SSLeay::AD_NO_APPLICATION_PROTOCOL();
    return (Net::SSLeay::CLIENT_HELLO_SUCCESS(), $al);
}

# Catch incorrectly implemented callbacks. A callback can not return
# too few values
sub client_hello_cb_no_return_value
{
    my ($ssl, $arg) = @_;

    pass('client_hello_cb_no_return_value called');
    return;
}

# Catch incorrectly implemented callbacks. A callback can not return
# too many values
sub client_hello_cb_too_many_return_values
{
    my ($ssl, $arg) = @_;

    pass('client_hello_cb_too_many_return_values called');
    my $al = Net::SSLeay::AD_NO_APPLICATION_PROTOCOL();
    return (Net::SSLeay::CLIENT_HELLO_SUCCESS(), $al, 'surprise');
}

# Definitions for tests. Each array entry defines a test round with
# instructions for both TLS client and server.
my @cb_tests = (
    # SSL_client_hello_cb_fn - callback function
    # argument passed to the callback
    # true if the callback function triggers croak()
    # true if the client needs to test that ALPN alert (120) is received
    [ \&client_hello_cb_v2hello_detection, undef, 0 ],
    [ \&client_hello_cb_getters, undef, 0 ],
    [ \&client_hello_cb_value_passing, \$cb_test_arg, 0 ],
    [ \&client_hello_cb_alert_alpn, undef, 0, 'alerts'],
    [ \&client_hello_cb_alert_alpn, undef, 0, 'alerts'], # Call again to increase alert counter
    [ \&client_hello_cb_conflicting_return_value, undef, 0 ],
    [ \&client_hello_cb_no_return_value, undef, 'croaks' ],
    [ \&client_hello_cb_too_many_return_values, undef, 'croaks' ],
    );

# Array that collects tests the client does. These are evaluated after
# the server has done all its tests. This is to keep the server and
# client test output from being incorrectly interleaved.
my @results;

{
    # SSL server
    $pid = fork();
    BAIL_OUT("failed to fork: $!") unless defined $pid;
    if ($pid == 0) {
	foreach my $cb_test (@cb_tests) {
	    my $ns = $server->accept();

	    my ($ctx, $proto) = new_ctx('TLSv1.2', 'TLSv1.3');
	    Net::SSLeay::set_cert_and_key($ctx, $cert_pem, $key_pem);

	    # TLSv1.3 servers send session tickets after the handshake; if a client
	    # closes the connection before the server sends the tickets, accept()
	    # fails with SSL_ERROR_SYSCALL and errno=EPIPE, which will cause this
	    # process to receive a SIGPIPE signal and exit unsuccessfully
	    Net::SSLeay::CTX_set_num_tickets($ctx, 0);

	    Net::SSLeay::CTX_set_client_hello_cb($ctx, $cb_test->[0], $cb_test->[1]);

	    my $ssl = Net::SSLeay::new($ctx);
	    Net::SSLeay::set_fd($ssl, fileno($ns));

	    # Some of test info_cbs attempt to trigger croak() which
	    # needs to be caught and tested here.
	    my $lives = eval { Net::SSLeay::accept($ssl); return 1; };
	    chomp(my $reason = $@);
	    if ($cb_test->[2])
	    {
		$lives ?
		    fail('ssl_client_hello_cb_fn did not die') :
		    like($@, qr/ssl_client_hello_cb_fn perl function returned/, "Died because of ssl_client_hello_cb_fn: $reason");
	    } else
	    {
		$lives ?
		    pass('ssl_client_hello_cb_fn did not die') :
		    fail("Died with reason: $reason");
	    }

	    Net::SSLeay::free($ssl);
	    Net::SSLeay::CTX_free($ctx);
	    close($ns) || die("server close: $!");
	}
        $server->close() || die("server listen socket close: $!");
        exit(0);
    }
}

{
    # SSL client
    my $alpn_alert_count = 0;

    # Use info callback to count TLS alert 120 occurences (ALPN alert).
    my $infocb = sub {
        my ($ssl, $where, $ret) = @_;

        if ($where & Net::SSLeay::CB_ALERT()) {
	    $alpn_alert_count++ if Net::SSLeay::alert_desc_string_long($ret) =~ m/no application protocol/s;
	}
    };

    # Start with SSLv2 ClientHello detection test. Send a canned SSLv2
    # ClientHello.
    {
	my $s_clientv2 = $server->connect();
	my $clientv2_hello = get_sslv2_hello();
	syswrite($s_clientv2, $clientv2_hello, length $clientv2_hello);
	sysread($s_clientv2, my $buf, 16384);

	# Alert (15), version (0303|4), length (0002), level fatal (02), bad cert(2a)
	my $alert_matches = unpack('H*', $buf) =~ m/^15030.0002022a\z/s;
	push @results, [$alert_matches, 'Client: Alert from canned SSLv2 ClientHello'];
	close($s_clientv2) || die("s_clientv2 close");
	shift @cb_tests;
    }

    # Start with TLSv1.3 ClientHello detection test. Send a canned TLSv1.3
    # ClientHello.
    {
	my $s_clientv2 = $server->connect();
	my $clientv2_hello = get_tlsv13_hello();
	syswrite($s_clientv2, $clientv2_hello, length $clientv2_hello);
	sysread($s_clientv2, my $buf, 16384);

	# Alert (15), version (0303|4), length (0002), level fatal (02), handshake failure(28)
	my $alert_matches = unpack('H*', $buf) =~ m/^15030.00020228\z/s;
	push @results, [$alert_matches, 'Client: Alert from canned TLS ClientHello'];
	close($s_clientv2) || die("s_clientv2 close");
	shift @cb_tests;
    }

    # The rest of tests use client's TLS stack
    foreach my $cb_test (@cb_tests) {
	my $s_c = $server->connect();

	my ($ctx_c, $proto_c) = new_ctx('TLSv1.2', 'TLSv1.3');
	Net::SSLeay::CTX_set_info_callback($ctx_c, $infocb)
	    if $cb_test->[3];

	# Add ALPN extension to ClientHello. We can then test that our
	# code finds it on the server side. We don't otherwise use
	# ALPN.
	my $rv = Net::SSLeay::CTX_set_alpn_protos($ctx_c, ['foo/1','bar/2']);

	Net::SSLeay::CTX_set_options($ctx_c, Net::SSLeay::OP_ALL());
	my $ssl_c = Net::SSLeay::new($ctx_c);
	Net::SSLeay::set_fd($ssl_c, $s_c);
	Net::SSLeay::connect($ssl_c);

	Net::SSLeay::free($ssl_c);
	Net::SSLeay::CTX_free($ctx_c);
	close($s_c) || die("client close: $!");
    }
    $server->close() || die("client listen socket close: $!");
    push @results, [$alpn_alert_count == 2, "Client: ALPN alert count is correct: got $alpn_alert_count"];
}

waitpid $pid, 0;
push @results, [$? == 0, 'Client: server exited with 0'];
END {
  Test::More->builder->current_test(37);
  ok( $_->[0], $_->[1] ) for (@results);
}

# Use a canned SSLv2 ClientHello for testing OpenSSL's
# SSL_client_hello_isv2()
sub get_sslv2_hello
{
    # Captures with OpenSSL 0.9.8f. The second capture uses TLSv1.0 as
    # Version but still includes a number of SSLv2 ciphersuites.
    #
    # openssl s_client -connect 127.0.0.1:443 -ssl2
    # openssl s_client -connect 127.0.0.1:443
    my $sslv2_sslv2_hex_f = '802e0100020015000000100700c00500800300800100800600400400800200808f11701ccdc4eab421b6d03e4942ea98';
    my $sslv2_tlsv1_hex_f = '807a01030100510000002000003900003800003500001600001300000a0700c000003300003200002f0000070500800300800000050000040100800000150000120000090600400000140000110000080000060400800000030200807f0913623fe5e84de01bc7733ae8fcdcefda1ef60a4c960ac7251f6560841566';

    # Captures with OpenSSL 0.9.8zh.
    #
    # The first capture is similar to 0.9.8f but the ciphersuites are
    # now ordered with the strongest first.The second capture uses
    # TLSv1.0 as Version but compared to 0.9.8f has a more modern set
    # of ciphers including TLS_EMPTY_RENEGOTIATION_INFO_SCSV.
    my $sslv2_sslv2_hex_zh = '802e0100020015000000100700c006004005008004008003008002008001008015c9eb78cbf9702542ac2d4c46b6101a';
    my $sslv2_tlsv1_hex_zh = '805901030100300000002000003900003800003500001600001300000a00003300003200002f0000070000050000040000150000120000090000ff1f90dda05ec4a857523dcc0ae06c461a99c36ce647a84aa64061c054333376b9';

    return pack('H*', $sslv2_tlsv1_hex_zh);
}

# Use a canned TLS ClientHello for testing the different get functions
sub get_tlsv13_hello
{
    # Capture with locally confgured OpenSSL 3.1.2
    #
    # openssl s_client -connect 127.0.0.1:443 -cipher ALL:@SECLEVEL=0
    my $tlsv13_hex = '160301019a0100019603038bbef485edd728d6c02c421b5a9a3a137d6dfda43c5796ef825d8ac7dcbbbc53200d687c7511cb0b65eb3cde414c2385bc0ecb56d8c81403c571184c4acbd1ee3100ae130213031301c02cc03000a3009fcca9cca8ccaac0afc0adc0a3c09fc05dc061c057c05300a7c02bc02f00a2009ec0aec0acc0a2c09ec05cc060c056c05200a6c024c028006b006ac073c07700c400c3006d00c5c023c02700670040c072c07600be00bd006c00bfc00ac0140039003800880087c019003a0089c009c0130033003200450044c01800340046009dc0a1c09dc051009cc0a0c09cc050003d00c0003c00ba00350084002f004100ff0100009f000b000403000102000a00160014001d0017001e0019001801000101010201030104002300000016000000170000000d0030002e040305030603080708080809080a080b080408050806040105010601030302030301020103020202040205020602002b0009080304030303020301002d00020101003300260024001d0020330c4636c46839dcd22288191791649290b432ed8748a8d7935799dc6e37f246';

    return pack('H*', $tlsv13_hex);
}
