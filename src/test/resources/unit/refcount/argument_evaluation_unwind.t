use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);

our (%weak, %destroyed, $entered);
$entered = 0;
{
    package ArgumentUnwindGuard;
    sub DESTROY { ++$main::destroyed{$_[0]{name}} }
}
{
    package ArgumentUnwindReceiver;
    sub accept { ++$main::entered; return scalar @_ }
    sub reject { ++$main::entered; die "callee failed\n" }
}
sub make_argument {
    my ($name) = @_;
    my $object = bless { name => $name }, 'ArgumentUnwindGuard';
    $weak{$name} = $object;
    weaken($weak{$name});
    return $object;
}
sub make_pair {
    my $object = make_argument('pair');
    return ($object, $object);
}

eval { ArgumentUnwindReceiver->accept(make_argument('abandoned'), die "later failed\n") };
like($@, qr/later failed/, 'later argument failure propagates');
is($entered, 0, 'failure during arguments never enters the callee');
ok(!defined $weak{abandoned}, 'abandoned earlier argument releases its referent');
is($destroyed{abandoned}, 1, 'abandoned referent is destroyed exactly once');

eval { ArgumentUnwindReceiver->accept(make_pair(), die "pair failed\n") };
like($@, qr/pair failed/, 'failure after a repeated return list propagates');
is($entered, 0, 'failed list arguments never enter the callee');
ok(!defined $weak{pair}, 'all repeated argument owners are released');
is($destroyed{pair}, 1, 'repeated argument edges destroy their referent once');

my $result = ArgumentUnwindReceiver->accept(make_argument('outer'), do {
    eval { ArgumentUnwindReceiver->accept(make_argument('inner'), die "inner failed\n") };
    like($@, qr/inner failed/, 'inner argument failure is caught locally');
    ok(!defined $weak{inner}, 'inner abandoned argument is released');
    ok(defined $weak{outer}, 'inner unwinding preserves the outer staged argument');
    1;
});
is($result, 3, 'outer call receives its invocant and both arguments');
is($entered, 1, 'only the outer call entered the callee');
is($destroyed{inner}, 1, 'inner referent is destroyed once');
ok(!defined $weak{outer}, 'successful outer call releases its temporary argument');
is($destroyed{outer}, 1, 'outer referent is destroyed once');

eval { ArgumentUnwindReceiver->reject(make_argument('callee')) };
like($@, qr/callee failed/, 'callee exception propagates');
is($entered, 2, 'throwing callee was entered once');
ok(!defined $weak{callee}, 'callee exception releases the argument');
is($destroyed{callee}, 1, 'callee exception destroys the argument once');

done_testing();
