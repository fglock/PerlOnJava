use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);

our (%weak, %destroyed, $entered);
$entered = 0;
{ package ArgumentReturnGuard; sub DESTROY { ++$main::destroyed{$_[0]{name}} } }
{ package ArgumentReturnReceiver; sub accept { ++$main::entered } }
sub make_argument {
    my ($name) = @_;
    my $object = bless { name => $name }, 'ArgumentReturnGuard';
    $weak{$name} = $object;
    weaken($weak{$name});
    return $object;
}
sub escape_scalar {
    ArgumentReturnReceiver->accept(make_argument('scalar'), do { return 'escaped' });
    die 'unreachable';
}
is(escape_scalar(), 'escaped', 'return during argument evaluation leaves the containing sub');
is($entered, 0, 'return during arguments never enters the callee');
ok(!defined $weak{scalar}, 'return releases the abandoned argument');
is($destroyed{scalar}, 1, 'return destroys the abandoned argument once');

sub escape_reference {
    ArgumentReturnReceiver->accept(make_argument('reference'), do {
        return make_argument('result');
    });
    die 'unreachable';
}
my $result = escape_reference();
is($entered, 0, 'reference return also bypasses the callee');
ok(!defined $weak{reference}, 'reference return releases the abandoned argument');
is($destroyed{reference}, 1, 'reference return destroys the abandoned argument once');
ok(defined $weak{result}, 'receiving caller owns the returned reference');
is($result->{name}, 'result', 'returned reference keeps its contents');
undef $result;
ok(!defined $weak{result}, 'returned reference is released by its receiver');
is($destroyed{result}, 1, 'returned reference is destroyed once');

sub escape_map {
    my @unused = map {
        ArgumentReturnReceiver->accept(make_argument('map'), do {
            return make_argument('map-result');
        });
    } 1;
    die 'unreachable';
}
my $map_result = escape_map();
is($entered, 0, 'non-local map return bypasses the callee');
ok(!defined $weak{map}, 'non-local return releases the abandoned argument');
is($destroyed{map}, 1, 'non-local return destroys the abandoned argument once');
ok(defined $weak{'map-result'}, 'non-local return preserves its receiving owner');
is($map_result->{name}, 'map-result', 'non-local return preserves result contents');
undef $map_result;
ok(!defined $weak{'map-result'}, 'receiver releases the non-local return result');
is($destroyed{'map-result'}, 1, 'non-local return result is destroyed once');

done_testing();
