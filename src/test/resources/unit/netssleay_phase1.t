#!/usr/bin/env perl
# Net::SSLeay Phase 1 — ERR queue + BIO memory buffers.
#
# Verifies the behaviour that netssleay_complete.md Phase 1 requires as
# a foundation for the handshake driver. Should pass regardless of
# whether Phase 2+ have landed.
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

# ------------------------------------------------------------------
# ERR queue
# ------------------------------------------------------------------

Net::SSLeay::ERR_clear_error();
is( Net::SSLeay::ERR_get_error(), 0, "ERR_get_error returns 0 on empty queue" );
is( Net::SSLeay::ERR_peek_error(), 0, "ERR_peek_error returns 0 on empty queue" );

# put-peek-get-empty round trip
Net::SSLeay::ERR_put_error(20, 0, 123, "file.c", 42);   # lib=20 (X509), reason=123
my $peek = Net::SSLeay::ERR_peek_error();
ok( $peek != 0, "ERR_peek_error sees the queued error" );
is( Net::SSLeay::ERR_peek_error(), $peek,
    "ERR_peek_error does not consume" );
my $got = Net::SSLeay::ERR_get_error();
is( $got, $peek, "ERR_get_error returns same code that ERR_peek_error showed" );
is( Net::SSLeay::ERR_get_error(), 0, "queue empty again after ERR_get_error" );

# error-string formatting
Net::SSLeay::ERR_put_error(20, 0, 42, "f.c", 1);
my $code = Net::SSLeay::ERR_get_error();
my $str = Net::SSLeay::ERR_error_string($code);
like( $str, qr/^error:[0-9A-Fa-f]+:/, "ERR_error_string format 'error:HEX:...'" );
like( $str, qr/X509/i, "ERR_error_string names the library (X509 for lib=20)" );

# ERR_clear_error wipes the whole queue
Net::SSLeay::ERR_put_error(20, 0, 1, "", 0);
Net::SSLeay::ERR_put_error(20, 0, 2, "", 0);
Net::SSLeay::ERR_clear_error();
is( Net::SSLeay::ERR_peek_error(), 0,
    "ERR_clear_error drains multi-entry queue" );

# ERR_print_errors_cb iterates with (line, len, user_data)
Net::SSLeay::ERR_put_error(20, 0, 10, "", 0);
Net::SSLeay::ERR_put_error(20, 0, 20, "", 0);
my @seen;
Net::SSLeay::ERR_print_errors_cb(sub {
    my ($line, $len, $ud) = @_;
    push @seen, [ $line, $len, $ud ];
    return 1;  # keep iterating
}, "user_ctx");
is( scalar @seen, 2, "ERR_print_errors_cb visits every queued entry" );
is( $seen[0][2], "user_ctx", "ERR_print_errors_cb threads user_data" );
like( $seen[0][0], qr/^error:/, "ERR_print_errors_cb passes formatted line" );
is( $seen[0][1], length $seen[0][0], "ERR_print_errors_cb passes line length" );

# Callback returning 0 stops iteration early
Net::SSLeay::ERR_put_error(20, 0, 10, "", 0);
Net::SSLeay::ERR_put_error(20, 0, 20, "", 0);
Net::SSLeay::ERR_put_error(20, 0, 30, "", 0);
my $count = 0;
Net::SSLeay::ERR_print_errors_cb(sub { $count++; 0 }, undef);
is( $count, 1, "callback returning 0 stops iteration" );
Net::SSLeay::ERR_clear_error();

# The *_load_*_strings functions are no-ops in modern OpenSSL.
ok( defined eval { Net::SSLeay::ERR_load_BIO_strings(); 1 },
    "ERR_load_BIO_strings returns without dying" );
ok( defined eval { Net::SSLeay::ERR_load_ERR_strings(); 1 },
    "ERR_load_ERR_strings returns without dying" );
ok( defined eval { Net::SSLeay::ERR_load_SSL_strings(); 1 },
    "ERR_load_SSL_strings returns without dying" );

# ------------------------------------------------------------------
# BIO memory buffers
# ------------------------------------------------------------------

my $bio = Net::SSLeay::BIO_new(Net::SSLeay::BIO_s_mem());
ok( $bio, "BIO_new allocated handle" );
is( Net::SSLeay::BIO_pending($bio), 0, "empty BIO reports 0 pending bytes" );
is( Net::SSLeay::BIO_eof($bio), 1, "empty BIO reports EOF" );

# write → pending
is( Net::SSLeay::BIO_write($bio, "hello"), 5, "BIO_write returns bytes written" );
is( Net::SSLeay::BIO_pending($bio), 5, "BIO_pending after write" );
is( Net::SSLeay::BIO_eof($bio), 0, "BIO_eof false after write" );

# read everything
my $buf = Net::SSLeay::BIO_read($bio);
is( $buf, "hello", "BIO_read returns written data" );
is( Net::SSLeay::BIO_pending($bio), 0, "BIO empty after full read" );
is( Net::SSLeay::BIO_eof($bio), 1, "BIO EOF after full read" );

# append-then-append semantics (chunked write, single read)
Net::SSLeay::BIO_write($bio, "abc");
Net::SSLeay::BIO_write($bio, "def");
is( Net::SSLeay::BIO_pending($bio), 6, "BIO_pending sees both chunks" );
is( Net::SSLeay::BIO_read($bio), "abcdef", "BIO_read concatenates chunks" );

# partial read: second arg is max bytes
Net::SSLeay::BIO_write($bio, "0123456789");
is( Net::SSLeay::BIO_read($bio, 4), "0123", "BIO_read respects max_len" );
is( Net::SSLeay::BIO_pending($bio), 6, "BIO_pending reflects bytes left" );
is( Net::SSLeay::BIO_read($bio), "456789", "remaining bytes readable" );

# BIO_new_mem_buf
my $ro = Net::SSLeay::BIO_new_mem_buf("roger");
ok( $ro, "BIO_new_mem_buf returns handle" );
is( Net::SSLeay::BIO_pending($ro), 5, "BIO_new_mem_buf seeds length" );
is( Net::SSLeay::BIO_read($ro), "roger", "BIO_new_mem_buf data readable" );
is( Net::SSLeay::BIO_read($ro), "", "subsequent read returns empty" );

# BIO_new_mem_buf with explicit len clips string
my $ro2 = Net::SSLeay::BIO_new_mem_buf("abcdefghij", 4);
is( Net::SSLeay::BIO_pending($ro2), 4, "BIO_new_mem_buf honours len argument" );
is( Net::SSLeay::BIO_read($ro2), "abcd", "BIO_new_mem_buf data matches clip" );

# BIO_s_file returns a sentinel we can pass to BIO_new without crashing
# (the actual file BIO is typically accessed via BIO_new_file)
my $file_method = Net::SSLeay::BIO_s_file();
ok( defined $file_method, "BIO_s_file returns defined sentinel" );

# BIO_free cleans up — subsequent use should not corrupt adjacent BIOs
my $bio1 = Net::SSLeay::BIO_new(Net::SSLeay::BIO_s_mem());
my $bio2 = Net::SSLeay::BIO_new(Net::SSLeay::BIO_s_mem());
Net::SSLeay::BIO_write($bio1, "aaa");
Net::SSLeay::BIO_write($bio2, "bbb");
Net::SSLeay::BIO_free($bio1);
is( Net::SSLeay::BIO_read($bio2), "bbb",
    "BIO_free on one BIO leaves siblings intact" );

done_testing();
