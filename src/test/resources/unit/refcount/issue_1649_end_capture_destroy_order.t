use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);

our $DESTROYED = 0;
{
    package Issue1649::EndCapture;
    sub DESTROY { ++$main::DESTROYED }
}

my $object = bless {}, 'Issue1649::EndCapture';
my $weak = $object;
weaken($weak);

END {
    ok(defined $weak, 'END capture keeps referent alive');
    is($DESTROYED, 0, 'DESTROY waits until END releases the lexical');
    ok(defined $object, 'END reads the captured lexical');
    undef $object;
    is($DESTROYED, 1, 'DESTROY runs when END drops the lexical');
    ok(!defined $weak, 'weak reference clears after release');
    done_testing();
}
