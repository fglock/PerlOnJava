# D-W6.1 — Sub-installation drift reproducer.
#
# Tracing `PJ_DESTROY_TRACE=1 ./jperl -e 'use Class::MOP::Class'` revealed
# two specific patterns where anonymous CVs are getting refCount=0
# transiently with the walker gate disabled:
#
#   1. `Sub::Install`'s anon CVs during `install_sub({ code => $cv, ... })`.
#   2. `Module::Implementation`'s `try { ... } catch { ... }` block CVs.
#
# Both patterns share a shape: an anonymous CV is created, passed through
# `@_` to a subroutine, the subroutine stores or invokes it, and the
# original CV's container scope completes — and at that point the CV's
# cooperative refCount drops to zero even though the receiver's structure
# (a closure-captured array, a hash slot, a glob slot) still holds it.
#
# This file recreates each pattern in bare Perl.
use strict;
use warnings;
use Test::More;

# ---- Pattern A: install_sub-shaped pass-through --------------------------
# Mimics Sub::Install's `install_sub({ code => sub { ... }, ... })`.
# A hashref containing the anonymous CV is built, passed to a function,
# the function stores the CV in a package stash, the hashref scope ends.
sub install_via_args {
    my $args = shift;
    no strict 'refs';
    *{ $args->{into} . '::' . $args->{as} } = $args->{code};
}

install_via_args({
    code => sub { 'A-result' },
    into => 'D_W6_1_A',
    as   => 'method',
});

ok exists &D_W6_1_A::method, 'A: install_sub-shaped CV present in stash';
is D_W6_1_A->method, 'A-result',
    'A: install_sub-shaped CV callable after caller scope ends';

# ---- Pattern B: try/catch-shaped block invocation ------------------------
# Mimics `Try::Tiny`'s `try { ... } catch { ... }`. Two CVs are passed by
# argument; the receiver eval-runs the first, optionally calls the second.
sub mini_try {
    my ($try_cv, $catch_cv) = @_;
    my $r = eval { $try_cv->() };
    if (!defined $r && $catch_cv) {
        $r = $catch_cv->($@);
    }
    return $r;
}

is mini_try(sub { 'no-error' }), 'no-error',
    'B: try-shaped success path returns CV result';
is mini_try(sub { die "boom\n" }, sub { my $e = shift; "caught: $e" }),
    "caught: boom\n",
    'B: try-shaped error path runs catch CV';

# Loop variant — Module::Implementation does this in a list of candidates.
my @candidates = map {
    my $i = $_;
    sub { "try-$i" };
} (1 .. 10);
my $hit = 0;
for my $cv (@candidates) {
    $hit++ if mini_try($cv) =~ /^try-/;
}
is $hit, 10, 'B: 10 try-shaped CVs all callable through pass-through';

# ---- Pattern C: temp lexical drop, then call through stash ---------------
# This is the precise shape of Sub::Install's failure: the original lexical
# holding the CV is dropped after install_sub returns, leaving the stash
# slot as the only strong holder.
{
    no strict 'refs';
    my $temp_cv = sub { 'C-from-temp' };
    install_via_args({
        code => $temp_cv,
        into => 'D_W6_1_C',
        as   => 'method',
    });
    $temp_cv = undef;   # explicit drop
}
ok exists &D_W6_1_C::method, 'C: stash holds CV after temp dropped';
is D_W6_1_C->method, 'C-from-temp', 'C: stash CV still callable';

# ---- Pattern D: pass CV through @_ then return it ------------------------
# `Sub::Install` and many other frameworks pass a CV through one or more
# layers of indirection before installing it. Each layer's `shift`/`return`
# must preserve the refCount.
sub return_arg { return $_[0] }
sub indirect_return { return return_arg(shift) }
sub deep_return { return indirect_return(shift) }

{
    no strict 'refs';
    my $cv = sub { 'D-deep' };
    *{"D_W6_1_D::method"} = deep_return($cv);
    $cv = undef;
}
ok exists &D_W6_1_D::method, 'D: deeply-passed CV present in stash';
is D_W6_1_D->method, 'D-deep', 'D: deeply-passed CV callable';

done_testing;
