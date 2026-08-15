use strict;
use warnings;
use threads;
use threads::shared;

print "1..2\n";

my $destroyed :shared = 0;

{
    package SharedCapturedScalar;

    sub new {
        my $value = 1;
        my $self = \$value;
        threads::shared::share($self);
        return bless($self, shift);
    }

    sub DESTROY {
        lock($destroyed);
        ++$destroyed;
    }
}

{
    my $shared_scalar :shared;
    threads->create(sub { $shared_scalar = SharedCapturedScalar->new() })->join();
    print $destroyed == 0
        ? "ok 1 - shared object remains alive while shared scalar is in scope\n"
        : "not ok 1 - shared object remains alive while shared scalar is in scope\n";
}

print $destroyed == 1
    ? "ok 2 - child closure capture is released before shared scalar scope exit\n"
    : "not ok 2 - child closure capture is released before shared scalar scope exit\n";
