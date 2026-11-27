use strict;
use warnings;
use Scalar::Util qw(weaken);

our $capture_amnesty = 0;
our @weak_amnesty;

BEGIN {
    no warnings 'redefine';
    *CORE::GLOBAL::bless = sub {
        my $class = @_ > 1 ? $_[1] : caller();
        my $object = CORE::bless($_[0], $class);
        if ($capture_amnesty && $class eq 'Test2::EventFacet::Amnesty') {
            push @weak_amnesty, $object;
            weaken($weak_amnesty[-1]);
        }
        return $object;
    };
}

use Test::More;

{
    local $capture_amnesty = 1;
    local $TODO = 'exercise one temporary amnesty facet';
    ok(0, 'intentional TODO failure creates an amnesty facet');
}

is(scalar @weak_amnesty, 1, 'captured one TODO amnesty facet');
is(scalar(grep { defined $_ } @weak_amnesty), 0,
    'Test::Builder releases the TODO amnesty facet after sending the event');

done_testing;
