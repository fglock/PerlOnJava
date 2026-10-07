use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);
our $DESTROYED = 0;
{ package Issue1649::DieCapture; sub DESTROY { ++$main::DESTROYED } }
my $object = bless {}, 'Issue1649::DieCapture';
my $weak = $object;
weaken($weak);
END {
    ok(defined $weak, 'END reached through die keeps the referent alive');
    is($DESTROYED, 0, 'DESTROY waits until die-dispatched END releases the lexical');
    ok(defined $object, 'die-dispatched END reads the captured lexical');
    undef $object;
    is($DESTROYED, 1, 'DESTROY runs when die-dispatched END drops the lexical');
    ok(!defined $weak, 'weak reference clears after die-dispatched END release');
    done_testing();
    exit 0;
}
die "intentional top-level failure\n";
