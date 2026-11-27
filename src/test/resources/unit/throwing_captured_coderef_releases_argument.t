use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);

sub invoke_callback {
    my ($callback) = @_;
    $callback->();
}

my $weak_object;
{
    my $object = bless {}, 'Local::CallbackObject';
    weaken($weak_object = $object);
    my $callback = sub {
        my $captured_class = ref($object);
        die "expected callback exception for $captured_class\n";
    };

    my $error;
    eval { invoke_callback($callback); 1 } or $error = $@;
    like($error, qr/expected callback exception/,
        'the callback exception reaches the caller');
}

ok(!defined($weak_object),
    'exception unwinding releases the captured callback argument');
done_testing();
