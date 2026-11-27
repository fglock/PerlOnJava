use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);
our ($weak, $destroyed);
$destroyed = 0;
{ package EvalCallbackOwner; sub DESTROY { ++$main::destroyed } }
sub eval_number {
    my $result = eval {
        my $guard = bless { value => 41 }, 'EvalCallbackOwner';
        $weak = $guard;
        weaken($weak);
        map { return 42 } 1;
        return 99;
    };
    return [$result, 'after eval'];
}
is_deeply(eval_number(), [42, 'after eval'], 'callback return exits eval and resumes its surrounding sub');
ok(!defined $weak, 'callback return releases eval lexicals before the caller resumes');
is($destroyed, 1, 'eval lexical destructor runs exactly once');
sub eval_reference {
    my $result = eval {
        my $guard = bless { value => 73 }, 'EvalCallbackOwner';
        $weak = $guard;
        weaken($weak);
        map { return $guard } 1;
        return undef;
    };
    return $result;
}
my $result = eval_reference();
is($result->{value}, 73, 'callback return preserves an eval-local reference');
ok(defined $weak, 'receiving caller owns the returned reference');
is($destroyed, 1, 'returned eval lexical stays alive');
undef $result;
ok(!defined $weak, 'last caller owner releases the returned eval lexical');
is($destroyed, 2, 'each eval object is destroyed once');
{
    package EvalOwnerReceiver;
    sub new { bless {}, shift }
    sub never { die "callback should have returned before entering method\n" }
}
sub eval_argument {
    my $result = eval {
        my $guard = bless { value => 95 }, 'EvalCallbackOwner';
        $weak = $guard;
        weaken($weak);
        EvalOwnerReceiver->new->never(map { return $guard } 1);
    };
    return $result;
}
$result = eval_argument();
is($result->{value}, 95, 'eval return survives abandoned method argument evaluation');
ok(defined $weak, 'abandoned method cleanup preserves the eval return owner');
undef $result;
ok(!defined $weak, 'abandoned method return releases at its last caller');
is($destroyed, 3, 'abandoned method return destroys the object once');
done_testing();
