use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);
our $destroyed = 0;
{
    package CountedTieHandler;
    sub TIEHASH { $_[1] }
    sub TIEARRAY { $_[1] }
    sub TIESCALAR { $_[1] }
    sub DESTROY { ++$main::destroyed }
}
my (%hash, @array, $scalar);
my $handler = bless {}, 'CountedTieHandler';
my $weak = $handler;
weaken($weak);
tie %hash, 'CountedTieHandler', $handler;
tie @array, 'CountedTieHandler', $handler;
tie $scalar, 'CountedTieHandler', $handler;
undef $handler;
ok(defined $weak, 'each tie keeps its handler after the source scalar is cleared');
{
    no warnings 'untie';
    untie %hash;
}
ok(defined $weak, 'array and scalar ties retain the handler after hash untie');
is($destroyed, 0, 'hash untie does not destroy a shared handler');
{
    no warnings 'untie';
    untie @array;
}
ok(defined $weak, 'scalar tie retains the handler after array untie');
is($destroyed, 0, 'array untie does not destroy a shared handler');
untie $scalar;
ok(!defined $weak, 'last tie releases the handler');
is($destroyed, 1, 'handler is destroyed exactly once');
untie %hash;
untie @array;
untie $scalar;
is($destroyed, 1, 'repeated untie does not release another owner');
{
    package CountedTieOuter;
    sub TIEHASH { $_[1] }
    sub DESTROY {
        my $self = shift;
        ++$main::destroyed unless tied(%$self);
    }
    sub wrap {
        my $inner = shift;
        my %outer;
        tie %outer, __PACKAGE__, $inner;
        bless \%outer, __PACKAGE__;
    }
}
$destroyed = 0;
my $weak_inner;
{
    my $outer = (bless {}, 'CountedTieOuter')->wrap;
    $weak_inner = tied(%$outer);
    weaken($weak_inner);
    ok(defined $weak_inner, 'tied outer owns its inner handler');
}
ok(!defined $weak_inner, 'destroying outer releases its inner handler before the next statement');
is($destroyed, 1, 'inner handler destructor runs exactly once after outer scope exit');
{
    package BorrowedTieRestoration;
    sub TIEHASH { bless {}, shift }
    sub EXISTS { 0 }
    sub FETCH { undef }
    sub STORE { }
    sub DELETE { die "restoring tied entry\n" }
    sub DESTROY { ++$main::destroyed }
}
my $before = $destroyed;
eval {
    my %tied;
    tie %tied, 'BorrowedTieRestoration';
    local $tied{key} = 'value';
    die "body failure\n";
};
is($@, "restoring tied entry\n", 'local restoration can borrow the handler after its lexical owner retires');
is($destroyed, $before + 1, 'exception unwind retires the handler owner once');
done_testing();
