use strict;
use warnings;
use Carp qw(carp);
use Scalar::Util qw(refaddr weaken);
use Test::More tests => 1;

our @captured_args;
my %destruction_registry;

sub detected_reinvoked_destroy {
    my ($object) = @_;
    my $address = refaddr($object);
    if (!defined $destruction_registry{$address}) {
        $destruction_registry{$address} = $object;
        weaken($destruction_registry{$address});
        return 0;
    }

    carp('Preventing *MULTIPLE* DESTROY() invocations on Guard');
    return 1;
}

{
    package CallerArgsGuard;

    sub new { bless {}, shift }

    sub DESTROY {
        return if main::detected_reinvoked_destroy($_[0]);

        my $self = shift;
        Carp::carp('first destruction warning');
    }
}

{
    local $SIG{__WARN__} = sub {
        package DB;
        my $frame = 0;
        while (my @caller = caller(++$frame)) {
            push @main::captured_args, @DB::args;
        }
    };

    my $guard = CallerArgsGuard->new();
    undef $guard;
}

ok(
    grep({ ref($_) eq 'CallerArgsGuard' } @captured_args),
    'caller() exposes DESTROY invocation args after the callee shifts @_'
);
