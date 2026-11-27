use strict;
use warnings;
use Scalar::Util qw(weaken);

our %captured_events;
our $captured_event_count = 0;
our $capture_events = 0;

BEGIN {
    no warnings 'redefine';
    *CORE::GLOBAL::bless = sub {
        my $class = @_ > 1 ? $_[1] : caller();
        my $object = CORE::bless($_[0], $class);
        if ($capture_events
            && ($class eq 'Test2::Event::Ok'
                || $class eq 'Test2::EventFacet::Trace')) {
            my $id = ++$captured_event_count;
            $captured_events{$id} = { class => $class, object => $object };
            weaken($captured_events{$id}{object});
        }
        return $object;
    };
}

use Test::More;

{
    local $capture_events = 1;
    ok(1, 'Test::Builder sends an assertion event');
}

my %captured_class = map { $_->{class} => 1 } values %captured_events;
ok($captured_class{'Test2::Event::Ok'}, 'captured the assertion event');
ok($captured_class{'Test2::EventFacet::Trace'}, 'captured its trace facet');

my @live = sort map { $_->{class} }
    grep { defined $_->{object} } values %captured_events;
is_deeply(\@live, [], 'Test::Builder releases assertion events after sending them');

done_testing;
