use strict;
use warnings;
use Test::More tests => 2;

{
    package EvalUnwindGuard;
    sub DESTROY { $_[0]->() }
}

my $observed_error;
$@ = "before\n";
eval {
    $@ = "inside\n";
    my $guard = bless(sub { $observed_error = $@ }, 'EvalUnwindGuard');
    1;
};

is($observed_error, "inside\n", 'eval keeps its error value during destructor unwinding');
is($@, '', 'successful eval clears the error after destructor unwinding');
