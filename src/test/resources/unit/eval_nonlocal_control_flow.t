use strict;
use warnings;

print "1..2\n";

our $destroyed = 0;
{
    package EvalControlGuard;
    sub make (&) { bless [$_[0]], __PACKAGE__ }
    sub DESTROY { $_[0][0]->() }
}

my $escaped;
ESCAPE: {
    my $guard = EvalControlGuard::make { $main::destroyed++ };
    my $callback = sub { last ESCAPE };
    eval { $callback->() };
    $escaped = 0;
}
$escaped = 1 if !defined $escaped;
print $escaped
    ? "ok 1 - labeled last crosses eval and callback boundaries\n"
    : "not ok 1 - labeled last crosses eval and callback boundaries\n";

print $destroyed == 1
    ? "ok 2 - labeled last tears down the exited lexical scope\n"
    : "not ok 2 - labeled last tears down the exited lexical scope\n";
