use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);

our $DESTROYED = 0;
{
    package Issue1649::ExitCapture;
    sub DESTROY { ++$main::DESTROYED }
}

my $object = bless {}, 'Issue1649::ExitCapture';
my $weak = $object;
weaken($weak);

END {
    ok(defined $weak, 'END reached through exit keeps the referent alive');
    is($DESTROYED, 0, 'DESTROY waits until exit-dispatched END releases the lexical');
    ok(defined $object, 'exit-dispatched END reads the captured lexical');
    undef $object;
    is($DESTROYED, 1, 'DESTROY runs when exit-dispatched END drops the lexical');
    ok(!defined $weak, 'weak reference clears after exit-dispatched END release');
    done_testing();
}

exit 0;
