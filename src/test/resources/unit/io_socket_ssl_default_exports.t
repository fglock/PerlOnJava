use strict;
use warnings;
use Test::More;

use IO::Socket::SSL;

is(SSL_VERIFY_NONE(), 0, 'SSL_VERIFY_NONE is exported by default');
is(SSL_VERIFY_PEER(), 1, 'SSL_VERIFY_PEER is exported by default');
is(SSL_VERIFY_FAIL_IF_NO_PEER_CERT(), 2,
   'SSL_VERIFY_FAIL_IF_NO_PEER_CERT is exported by default');
is(SSL_VERIFY_CLIENT_ONCE(), 4, 'SSL_VERIFY_CLIENT_ONCE is exported by default');

done_testing;
