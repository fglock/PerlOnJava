use strict;
use warnings;
use Test::More tests => 4;
use Scalar::Util qw(weaken);

eval q{
    package Ownership::CallSiteLifetimeCallee;
    sub target { 42 }
    1;
} or die $@;

# Resolve this at runtime so the test itself does not compile a separate direct
# reference to the CV into its main optree.
my $weak_callee = \&{'Ownership::CallSiteLifetimeCallee::target'};
weaken($weak_callee);

my $compiled_caller = eval q{
    sub { Ownership::CallSiteLifetimeCallee::target() }
};
die $@ unless $compiled_caller;
ok(defined $weak_callee, 'the named CV starts alive');

delete $Ownership::CallSiteLifetimeCallee::{target};
ok(defined $weak_callee, 'the compiled caller keeps the deleted callee alive');
is($compiled_caller->(), 42, 'the compiled callsite still invokes the deleted CV');

undef $compiled_caller;
ok(!defined $weak_callee, 'dropping the compiled caller releases its callee owner');
