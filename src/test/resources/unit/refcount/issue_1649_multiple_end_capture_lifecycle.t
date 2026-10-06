use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);

our $DESTROYED = 0;
{
    package Issue1649::MultipleEndCapture;
    sub DESTROY { ++$main::DESTROYED }
}

my $object = bless {}, 'Issue1649::MultipleEndCapture';
my $weak = $object;
weaken($weak);

END {
    is($DESTROYED, 0, 'earlier END block still owns the captured lexical');
    ok(defined $object, 'earlier END block can read the lexical');
    undef $object;
    is($DESTROYED, 1, 'last END lexical release runs DESTROY');
    ok(!defined $weak, 'weak reference clears after the last END release');
    done_testing();
}

END {
    is($DESTROYED, 0, 'later END block does not release a shared capture');
    ok(defined $object, 'later END block can read the captured lexical');
}
