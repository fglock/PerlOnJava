package threads;

use strict;
use warnings;

# Stub implementation matching standard Perl behavior
# Standard Perl's threads.pm checks useithreads and dies if not available

# Verify this Perl supports threads (mimicking standard Perl behavior)
require Config;
if (!$Config::Config{useithreads}) {
    die("This Perl not built to support threads\n");
}

# Declare that we have been loaded (standard Perl sets this)
our $threads = 1;

# tid() is needed by t/loc_tools.pl
sub tid {
    return 0;
}

# create() is called by watchdog() in t/test.pl
# Returns a dummy thread object
sub create {
    my ($class, $code) = @_;
    # Don't actually create a thread, just return a dummy object
    return bless {}, 'threads::DummyThread';
}

# Stub for threads::DummyThread
package threads::DummyThread;

sub detach {
    # Do nothing - we're not actually running a thread
    return 1;
}

sub kill {
    # Do nothing - we're not actually running a thread
    return 1;
}

sub is_running {
    # Always return false - thread is not running
    return 0;
}

package threads;

1;

