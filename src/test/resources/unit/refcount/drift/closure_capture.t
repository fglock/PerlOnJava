# D-W6.2 — Closure-capture drift reproducer.
#
# Tracing `PJ_DESTROY_TRACE=1 ./jperl -e 'use Class::MOP::Class'` showed
# anonymous CVs from Sub::Install being destroyed prematurely. The
# pattern is Sub::Install's nested closure wrappers:
#
#     *install_sub = _build_public_installer(_ignore_warnings(_installer));
#
# Each layer is `sub { ... my $code = shift; sub { $code->(@_) } }` —
# a closure that captures a CODE-ref my-var and returns a new closure
# using it. Three layers stack three levels of capture.
#
# The hypothesis (D-W6.2): when a closure captures a my-var holding a
# CODE ref, and the my-var's outer scope exits, PerlOnJava decrements
# the CODE ref's cooperative refCount even though the closure still
# references it. The walker gate masks this; without the gate the
# CODE ref's refCount goes negative and DESTROY fires.
use strict;
use warnings;
use Test::More;

# ---- Pattern A: single-layer wrap (baseline) -----------------------------
sub wrap_one {
    my $code = shift;
    sub { $code->(@_) };
}

{
    my $cv = sub { 'A-result' };
    my $wrapped = wrap_one($cv);
    $cv = undef;            # drop outer reference

    is $wrapped->(), 'A-result',
        'A: single-layer wrapped closure callable after outer ref dropped';
}

# ---- Pattern B: two-layer wrap -------------------------------------------
sub wrap_two_a {
    my $code = shift;
    sub { $code->(@_) };
}
sub wrap_two_b {
    my $code = shift;
    sub { my $r = $code->(@_); $r };
}

{
    my $cv = sub { 'B-result' };
    my $wrapped = wrap_two_b(wrap_two_a($cv));
    $cv = undef;

    is $wrapped->(), 'B-result',
        'B: two-layer wrapped closure callable';
}

# ---- Pattern C: three-layer wrap (Sub::Install shape) --------------------
# This is the precise install_sub pattern.
sub _installer {
    sub {
        my ($pkg, $name, $code) = @_;
        no strict 'refs';
        *{"${pkg}::${name}"} = $code;
        return $code;
    }
}

sub _ignore_warnings {
    my $code = shift;
    sub {
        local $SIG{__WARN__} = sub {};
        $code->(@_);
    };
}

sub _build_public_installer {
    my $installer = shift;
    sub {
        my $arg = shift;
        $installer->(@{$arg}{qw(into as code)});
    };
}

# Build the install function the way Sub::Install does it.
my $install_sub = _build_public_installer(_ignore_warnings(_installer()));

# The build helpers' temp lexicals (`$code`, `$installer`) are now out of
# scope — the only ref to each layer's CV is the next outer closure's
# capture.

$install_sub->({
    into => 'D_W6_2_C',
    as   => 'method',
    code => sub { 'C-result' },
});

ok exists &D_W6_2_C::method, 'C: three-layer install put method in stash';
is D_W6_2_C->method, 'C-result',
    'C: three-layer-installed method callable';

# ---- Pattern D: deep capture chain (5 levels) ----------------------------
sub make_layer {
    my $depth = shift;
    return sub { @_ } if $depth == 0;
    my $inner = make_layer($depth - 1);
    return sub { $inner->(@_) };
}

{
    my $top = make_layer(5);
    is_deeply [$top->('deep-1', 'deep-2')], ['deep-1', 'deep-2'],
        'D: 5-layer deep capture chain returns args';
}

# ---- Pattern E: closure captures a CV that captures a CV -----------------
# Each level captures the level below — refCount on each captured CV
# must not decay.
sub make_chain {
    my $tag = shift;
    my $inner = sub { "$tag-result" };
    return sub {
        my $extra = shift;
        return $inner->() . " ($extra)";
    };
}

my @chained = map { make_chain("E$_") } 1 .. 20;
my @results = map { $chained[$_]->("call$_") } 0 .. 19;
is scalar @results, 20, 'E: 20 chained closures all callable';
is $results[0], 'E1-result (call0)', 'E: first closure result';
is $results[19], 'E20-result (call19)', 'E: last closure result';

done_testing;
