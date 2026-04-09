
# Tests for SSL_CTX_set1_sigalgs_list and related functions

use lib 'inc';

use Net::SSLeay;
use Test::Net::SSLeay qw(can_fork data_file_path initialise_libssl new_ctx tcp_socket);

initialise_libssl();

if (!defined &Net::SSLeay::CTX_set1_sigalgs_list) {
    plan skip_all => "No CTX_set1_sigalgs_list()";
} else {
    plan tests => 23;
}

my $version_num = Net::SSLeay::OPENSSL_VERSION_NUMBER();

my ($ctx, $proto) = new_ctx('TLSv1.2', 'TLSv1.3');
my $ssl = Net::SSLeay::new($ctx);

# '?' in the list means that the algorithm can be ignored if it's not
# implemented
my @tests = (
    # TLSv1.3 list                   # TLSv1.2 list       # components in the list # retval
    ['rsa_pss_rsae_sha256',          'RSA+SHA1',          'valid',                 1],
    ['rsa_pss_rsae_sha256:invalid',  'RSA+SHA1:invalid',  'valid and invalid',     0],
    ['invalid',                      'invalid',           'invalid',               0],
    ['rsa_pss_rsae_sha256:?invalid', 'RSA+SHA1:?invalid', 'valid and ignored',     1],
    );

foreach my $test (@tests)
{
    my $list = $proto eq 'TLSv1.3' ? $test->[0] : $test->[1];

  SKIP: {
      # Support for ignoring a sigalg requires OpenSSL 3.0 and later
      skip "No support for ignoring signature algorithms in " . Net::SSLeay::SSLeay_version(), 4
	  if ($list =~ m/\?/s && $version_num < 0x30300000);
	is(Net::SSLeay::CTX_set1_sigalgs_list       ($ctx, $list), $test->[3], "$proto CTX_set1_sigalgs_list('$list') list is: $test->[2]");
	is(Net::SSLeay::CTX_set1_client_sigalgs_list($ctx, $list), $test->[3], "$proto CTX_set1_client_sigalgs_list('$list') list is: $test->[2]");
	is(Net::SSLeay::set1_sigalgs_list       ($ssl, $list), $test->[3], "$proto set1_sigalgs_list('$list') list is: $test->[2]");
	is(Net::SSLeay::set1_client_sigalgs_list($ssl, $list), $test->[3], "$proto set1_client_sigalgs_list('$list') list is: $test->[2]");
  }
}

my $pid;
alarm(30);
END { kill 9,$pid if $pid }

# Load file contents before fork to avoid failure on Windows.
# For more information, see
# https://github.com/radiator-software/p5-net-ssleay/issues/544
my $ca_file_pem = data_file_path('intermediate-ca.certchain.pem');
my $cert_pem = data_file_path('simple-cert.cert.pem');
my $key_pem  = data_file_path('simple-cert.key.pem');

# See client's cert_cb callback below for more background information
# about sigalgs function use in this callback.
my $server_msg_to_be_sent;
sub cert_cb_server {
   my ($ssl, $cb_data) = @_;

   my $idx = 0;
   my @peer_sigalgs = Net::SSLeay::get_sigalgs($ssl, $idx);
   my $peer_num_algs = $peer_sigalgs[0];

   my @shared_sigalgs = Net::SSLeay::get_shared_sigalgs($ssl, 0);
   my $shared_num_algs = $shared_sigalgs[0];

   $server_msg_to_be_sent = $cb_data . " $peer_num_algs $shared_num_algs";

   return 1;
}

# Filled in by server's cert_cb
my $server = tcp_socket();
{
    # SSL server - just handle single connect and  shutdown connection
    defined($pid = fork()) or BAIL_OUT("failed to fork: $!");
    if ($pid == 0) {
	my $cl = $server->accept();
	my $ctx = new_ctx();
	Net::SSLeay::CTX_load_verify_locations($ctx, $ca_file_pem, '');
	Net::SSLeay::set_cert_and_key($ctx, $cert_pem, $key_pem);
	Net::SSLeay::CTX_set_verify($ctx, (Net::SSLeay::VERIFY_PEER() | Net::SSLeay::VERIFY_FAIL_IF_NO_PEER_CERT()));

	# Set this variable from the certificate callback
	Net::SSLeay::CTX_set_cert_cb($ctx, \&cert_cb_server , 'server cert_cb called:');

	my $ssl = Net::SSLeay::new($ctx);
	Net::SSLeay::set_fd($ssl, fileno($cl));
	Net::SSLeay::accept($ssl);

	# Send the message that's been updated by server's cert_cb
	Net::SSLeay::write($ssl, $server_msg_to_be_sent);
	Net::SSLeay::shutdown($ssl);

	close($cl) || die("server close: $!");
        $server->close() || die("server listen socket close: $!");
        exit;
    }
}

# Why SSL_get_siglags and SSH_get_shared_sigalgs are tested within
# certificate callback? See the following quote from OpenSSL
# SSL_get_shared_sigalgs manual page:
#
# These functions must be called after the peer has sent a list of
# supported signature algorithms: after a client hello (for servers)
# or a certificate request (for clients). They can (for example) be
# called in the certificate callback.
sub cert_cb_client {
   my ($ssl, $cb_data) = @_;

   is($cb_data, 'client cert_cb arg', 'Client certificate callback was called');

   {
       my $idx = 0;
       my @peer_sigalgs = Net::SSLeay::get_sigalgs($ssl, $idx);
       my $num_algs = $peer_sigalgs[0];
       cmp_ok($num_algs, '>', 0, "client: get_sigalgs returns > 0 algs: $num_algs");

       while ($idx < $num_algs) {
	   @peer_sigalgs = Net::SSLeay::get_sigalgs($ssl, $idx++);
	   fail('Failed looping through get_sigalgs')
		if ($peer_sigalgs[0] != $num_algs || $peer_sigalgs[0] == 0);
       }
   }

   # Similar loop but this time for shared sigalgs
   {
       my $idx = 0;
       my @shared_sigalgs = Net::SSLeay::get_shared_sigalgs($ssl, $idx);
       my $num_algs = $shared_sigalgs[0];
       cmp_ok($num_algs, '>', 0, "client: get_shared_sigalgs returns > 0 algs: $num_algs");

	while ($idx < $num_algs) {
	    @shared_sigalgs = Net::SSLeay::get_shared_sigalgs($ssl, $idx++);
	    fail('Failed looping through get_shared_sigalgs')
		if ($shared_sigalgs[0] != $num_algs || $shared_sigalgs[0] == 0);
	}
   }

   return 1;
}

sub client {
    # SSL client - connect and shutdown

    my $cl = $server->connect();
    my $ctx = new_ctx();
    Net::SSLeay::set_cert_and_key($ctx, $cert_pem, $key_pem);
    Net::SSLeay::CTX_set_options($ctx, Net::SSLeay::OP_ALL());
    Net::SSLeay::CTX_set_cert_cb($ctx, \&cert_cb_client, 'client cert_cb arg');

    my $ssl = Net::SSLeay::new($ctx);
    Net::SSLeay::set1_client_sigalgs_list($ssl, 'rsa_pss_rsae_sha256');
    Net::SSLeay::set_fd($ssl, $cl);
    Net::SSLeay::connect($ssl);

    my $server_msg = Net::SSLeay::read($ssl);
    like($server_msg, qr/server cert_cb called: \d+ \d+/, 'Server certificate callback was called');
    my ($server_num_sigalgs, $server_num_shared_sigalgs) = ($server_msg =~ m/(\d+) (\d+)\z/s);
    cmp_ok($server_num_sigalgs,        '>', 0, "server: get_sigalgs returns > 0 algs: $server_num_sigalgs");
    cmp_ok($server_num_shared_sigalgs, '>', 0, "server: get_shared_sigalgs returns > 0 algs: $server_num_shared_sigalgs");

    Net::SSLeay::shutdown($ssl);

    close($cl) || die("client close: $!");

    my $unset_cb = eval {Net::SSLeay::CTX_set_cert_cb($ctx, undef); 1; };
    is($unset_cb, 1, "no error when removing the certificate callback");

    return;
}

client();
$server->close() || die("client listen socket close: $!");
waitpid $pid, 0;
