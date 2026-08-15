use strict;
use warnings;
use threads;
use threads::shared;

print "1..3\n";

{
    package SharedFetchCookie;

    sub new {
        my ($class, $flavour) = @_;
        my $self = bless(threads::shared::shared_clone({ flavour => $flavour }), $class);
        return $self;
    }

    sub DESTROY {
        delete shift->{flavour};
    }
}

my @jar :shared;
my $cookie = SharedFetchCookie->new('chocolate');
push @jar, $cookie;

sub fetch_and_discard {
    return $jar[-1];
}

fetch_and_discard();
print $cookie->{flavour} eq 'chocolate'
    ? "ok 1 - discarded fetch proxy does not destroy source object\n"
    : "not ok 1 - discarded fetch proxy does not destroy source object\n";
print $jar[-1]->{flavour} eq 'chocolate'
    ? "ok 2 - discarded fetch proxy leaves shared storage intact\n"
    : "not ok 2 - discarded fetch proxy leaves shared storage intact\n";

my $child_value = threads->create(sub { fetch_and_discard(); return $jar[-1]->{flavour} })->join();
print $child_value eq 'chocolate' && $jar[-1]->{flavour} eq 'chocolate'
    ? "ok 3 - child fetch proxy leaves canonical object intact\n"
    : "not ok 3 - child fetch proxy leaves canonical object intact\n";
