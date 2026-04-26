use strict;
use warnings;
print "1..6\n";

# Regression test for a bytecode-interpreter bug where SCOPE_EXIT_CLEANUP_HASH
# and SCOPE_EXIT_CLEANUP_ARRAY blindly cast their register slot to
# RuntimeHash / RuntimeArray. If a control-flow path skipped the
# my-hash / my-array initialisation (e.g. an early `return`, `last`,
# `goto`, or a short-circuit `&&`/`||` guarding the `my %h = (...)` /
# `my @a = (...)` declaration), the register could still hold a
# transient RuntimeScalar produced by an unrelated CREATE_LIST that
# recycled the same slot. The unconditional cast then threw
#   "class RuntimeScalar cannot be cast to class RuntimeHash"
#   "class RuntimeScalar cannot be cast to class RuntimeArray"
# at sub/scope exit, even though the user's logic completed normally.
#
# This test only triggers in the bytecode-interpreter backend (the
# JIT/JVM backend uses a different code path), so we have to coerce
# the offending sub onto the interpreter fallback path. The most
# reliable trigger today is `goto $coderef` (dynamic goto EXPR), which
# the JIT cannot currently emit and which forces the entire enclosing
# sub to be compiled to InterpretedCode and run by BytecodeInterpreter.
#
# Originally surfaced by `use Moose;`, which loads
# Sub::Exporter::Progressive::import — that sub uses
# `goto \&Exporter::import` and contains lexical hashes/arrays.
# Without this fix, every Moose-based test died at `use Moose;` with
# the ClassCastException above.
#
# !!! If this test starts failing, do NOT delete it. The fix lives in
# !!! BytecodeInterpreter.java around the SCOPE_EXIT_CLEANUP_HASH /
# !!! SCOPE_EXIT_CLEANUP_ARRAY opcodes (defensive instanceof checks).

# --- Test 1: my-hash + early return + goto-fallback ------------------
sub hash_then_return {
    my %h = (a => 1, b => 2);
    return "got=" . $h{a};
    # Unreachable code that forces the JIT to fall back to the
    # bytecode interpreter for this whole sub:
    my $f = sub { 1 };
    goto $f;
}
print "not " unless hash_then_return() eq 'got=1';
print "ok 1 - my %h + early return survives interpreter fallback\n";

# --- Test 2: my-array + early return + goto-fallback -----------------
sub array_then_return {
    my @a = ('x', 'y', 'z');
    return "len=" . scalar(@a);
    my $f = sub { 1 };
    goto $f;
}
print "not " unless array_then_return() eq 'len=3';
print "ok 2 - my \@a + early return survives interpreter fallback\n";

# --- Test 3: both my-hash and my-array in the same sub ---------------
sub mixed {
    my %h = (k => 'v');
    my @a = (10, 20);
    return "h=" . $h{k} . ";a=" . $a[1];
    my $f = sub { 1 };
    goto $f;
}
print "not " unless mixed() eq 'h=v;a=20';
print "ok 3 - mixed my %h and my \@a both cleaned up safely\n";

# --- Test 4: the real-world Moose-style trigger ----------------------
# This mirrors the line in Sub::Exporter::Progressive::import that
# originally exposed the bug: a complex `for` loop that aliases $_
# to multiple list elements, combined with a `goto \&...` later in
# the same sub.
sub progressive_like {
    my @exports  = ('foo', 'bar');
    my @defaults = (':all', 'baz');
    my %tags     = (default => ['x', 'y'], other => ['-all', 'z']);
    @{$_} = map { /\A[:-]all\z/ ? @exports : $_ } @{$_}
        for \@defaults, values %tags;
    return scalar(@defaults) . ',' . scalar(@{ $tags{other} });
    # Force interpreter fallback:
    my $f = sub { 1 };
    goto $f;
}
print "not " unless progressive_like() eq '3,3';
print "ok 4 - Sub::Exporter::Progressive-style for-loop pattern works\n";

# --- Test 5: many invocations, register-reuse stress -----------------
# Call the sub repeatedly so any lingering register reuse across
# invocations is exercised.
my $ok = 1;
for my $i (1 .. 100) {
    my $r = mixed();
    $ok = 0 unless defined $r && $r eq 'h=v;a=20';
}
print "not " unless $ok;
print "ok 5 - 100 iterations without scope-exit ClassCastException\n";

# --- Test 6: short-circuit-skipped my-hash + interpreter fallback ----
# Combine the short-circuit pattern (which my_short_circuit_scope_exit.t
# covers for scalars) with a my-hash on the interpreter path.
sub short_circuit_hash {
    my $arg = shift;
    if ( ref($arg)
         and UNIVERSAL::isa($arg, 'HASH')
         and defined( (my %copy = %$arg)
                      ? $copy{key}
                      : undef ) )
    {
        return "k=" . $copy{key};
    }
    return 'skipped';
    # Force interpreter fallback regardless of which branch ran:
    my $f = sub { 1 };
    goto $f;
}
my $r1 = short_circuit_hash(undef);
my $r2 = short_circuit_hash({ key => 'v' });
print "not " unless $r1 eq 'skipped' and $r2 eq 'k=v';
print "ok 6 - short-circuit-skipped my %h cleaned up safely\n";
