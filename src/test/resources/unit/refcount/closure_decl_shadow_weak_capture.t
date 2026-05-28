use strict;
use warnings;

use Test::More;
use Scalar::Util qw(weaken);

sub make_callback {
    my $self = bless {}, 'POJClosureDeclShadowWeakCapture';
    weaken(my $weakself = $self);

    my $callback = sub {
        return unless my $self = $weakself;
        return $self;
    };

    return ($self, $callback);
}

my ($object, $callback) = make_callback();
ok defined $callback->(), 'weak self is available while strong owner lives';

undef $object;
ok !defined $callback->(), 'inner my declaration does not capture outer strong self';

done_testing;
