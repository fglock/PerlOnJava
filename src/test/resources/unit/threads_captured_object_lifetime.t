use strict;
use warnings;

use threads;
use Scalar::Util qw(weaken);

print "1..2\n";

{
    package ThreadCapturedGuard;

    our $premature = 0;

    sub new {
        my $self = bless { attached => 0 }, $_[0];
        $self->{weak_self} = $self;
        Scalar::Util::weaken($self->{weak_self});
        return $self;
    }
    sub attach { $_[0]->{attached} = 1 }
    sub run { $_[1]->() }
    sub detach {
        die "captured guard was detached early" unless $_[0]->{attached};
        $_[0]->{attached} = 0;
    }
    sub DESTROY { $premature++ if $_[0]->{attached} }
}

my $guard = ThreadCapturedGuard->new;
my $thread = threads->create(sub {
    $guard->attach;
    $guard->run(sub { 1 });
    $guard->detach;
    return $ThreadCapturedGuard::premature;
});

my $child_premature = $thread->join;
print !$child_premature
    ? "ok 1 - captured child object survives through explicit detach\n"
    : "not ok 1 - captured child object survives through explicit detach\n";
print $guard->{attached} == 0
    ? "ok 2 - parent object remains independent\n"
    : "not ok 2 - parent object remains independent\n";
