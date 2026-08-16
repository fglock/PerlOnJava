use strict;
use warnings;
use lib 'src/test/resources/unit/lib';

print "1..6\n";

sub target { 'old' }
my $saved = \&target;
sub trampoline { goto &target }

{
    no warnings 'redefine';
    *target = sub { 'new' };
}

print trampoline() eq 'new'
    ? "ok 1 - goto named sub resolves the current CODE slot\n"
    : "not ok 1 - goto named sub resolves the current CODE slot\n";
print $saved->() eq 'old'
    ? "ok 2 - an ordinary saved coderef keeps the original CV\n"
    : "not ok 2 - an ordinary saved coderef keeps the original CV\n";

sub lazy_stub {
    no warnings 'redefine';
    *lazy_stub = sub { 'loaded' };
    goto &lazy_stub;
}

print lazy_stub() eq 'loaded'
    ? "ok 3 - a lazy stub can redefine and tail-call its replacement\n"
    : "not ok 3 - a lazy stub can redefine and tail-call its replacement\n";

package GotoNamedReplacement;

sub lazy_stub {
    my $loaded = do "GotoNamedReplacement.pm";
    die $@ if $@;
    die $! if ! defined $loaded;
    goto &lazy_stub;
}

package main;

print GotoNamedReplacement::lazy_stub() eq 'loaded from do'
    ? "ok 4 - do-file redefinition replaces a lazy named stub\n"
    : "not ok 4 - do-file redefinition replaces a lazy named stub\n";

package GotoWrappedReplacement;

sub lazy_stub {
    my $loaded = do "GotoWrappedReplacement.pm";
    die $@ if $@;
    die $! if ! defined $loaded;
    goto &lazy_stub;
}

my $original_stub = \&lazy_stub;
{
    no warnings 'redefine';
    *lazy_stub = sub { goto &$original_stub };
}

package main;

print GotoWrappedReplacement::lazy_stub() eq 'loaded through wrapper'
    ? "ok 5 - do-file redefinition replaces a wrapped lazy stub\n"
    : "not ok 5 - do-file redefinition replaces a wrapped lazy stub\n";

package GotoSymbolicWrappedReplacement;

sub lazy_stub {
    my $loaded = do "GotoSymbolicWrappedReplacement.pm";
    die $@ if $@;
    die $! if ! defined $loaded;
    goto &lazy_stub;
}

my $symbolic_original = \&lazy_stub;
my $symbolic_wrapper = sub { goto &$symbolic_original };
{
    no strict 'refs';
    no warnings 'redefine';
    *{'GotoSymbolicWrappedReplacement::lazy_stub'} = $symbolic_wrapper;
}

package main;

print GotoSymbolicWrappedReplacement::lazy_stub() eq 'loaded through symbolic wrapper'
    ? "ok 6 - do-file redefinition replaces a symbolic-glob wrapper\n"
    : "not ok 6 - do-file redefinition replaces a symbolic-glob wrapper\n";
