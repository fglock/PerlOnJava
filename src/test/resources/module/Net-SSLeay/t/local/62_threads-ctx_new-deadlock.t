use lib 'inc';

use Net::SSLeay;
use Test::Net::SSLeay qw( can_thread is_openssl initialise_libssl );

use FindBin;

if (not can_thread()) {
    plan skip_all => "Threads not supported on this system";
} elsif ($^O eq 'cygwin') {
    #XXX-TODO perhaps perl+ithreads related issue (needs more investigation)
    plan skip_all => "this test sometimes crashes on Cygwin";
} else {
    plan tests => 1;
}

require threads;

# OpenSSL 3.0 and later set atexit() handlers in such a way that this
# test may crash during the default cleanup on exit.  See
# https://github.com/openssl/openssl/issues/17469 and
# https://github.com/radiator-software/p5-net-ssleay/issues/452 for
# more information, including workarounds when OPENSSL_INIT_crypto()
# is not available in Net::SSLeay.
#
# If we need to do OPENSSL_INIT_crypto() call, we must skip the
# default library initialisation. Otherwise our call to
# OPENSSL_init_crypto() won't do anything.
if (is_openssl()) {
    eval { Net::SSLeay::OPENSSL_INIT_NO_ATEXIT(); return 1; } ?
	Net::SSLeay::OPENSSL_init_crypto(Net::SSLeay::OPENSSL_INIT_NO_ATEXIT(), undef) :
	initialise_libssl();
} else {
    # At the time of writing OPENSSL_init_crypto is not exposed with
    # LibreSSL. Even if it were exposed we can skip atexit() special
    # handling because LibreSSL 4.1.0 release notes state the
    # following:
    #   Added an OPENSSL_INIT_NO_ATEXIT flag for OPENSSL_init_crypto().
    #   It has no effect since LibreSSL doesn't call atexit().
    initialise_libssl();
}

my $start_time = time;

#exit the whole program if it runs too long
threads->new( sub { sleep 30; warn "FATAL: TIMEOUT!"; exit } )->detach;

#print STDERR "Gonna start multi-threading part\n";
threads->new(\&do_check) for (1..100);

#print STDERR "Waiting for all threads to finish\n";
do_sleep(50) while (threads->list());

pass("successfully finished, duration=".(time-$start_time));
exit(0);

sub do_sleep {
  my $miliseconds = shift;
  select(undef, undef, undef, $miliseconds/1000);
}

sub do_check {
  #printf STDERR ("[thread:%04d] do_check started\n", threads->tid);
  
  my $c = Net::SSLeay::CTX_new() or warn "CTX_new failed" and exit;
  my $d = Net::SSLeay::new($c) or warn "SSL_new" and exit;
  my $e = Net::SSLeay::SESSION_new() or warn "SSL_SESSION_new failed" and exit;
  Net::SSLeay::set_session($d,$e);
  Net::SSLeay::SESSION_free($e);
  Net::SSLeay::free($d);
  Net::SSLeay::CTX_free($c);
    
  #printf STDERR ("[thread:%04d] do_check finished\n", threads->tid);
  threads->detach();
}
