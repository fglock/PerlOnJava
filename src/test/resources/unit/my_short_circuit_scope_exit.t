use strict;
use warnings;
print "1..6\n";

# Regression test for the interpreter-backend bug where a `my` variable
# declared inside a short-circuiting expression left stale data in its
# register when the short-circuit skipped the initialisation. When the
# enclosing scope exited, SCOPE_EXIT_CLEANUP crashed with
# "RuntimeList cannot be cast to RuntimeScalar".
#
# Before the fix this pattern would crash reliably under the
# JVM->interpreter fallback path when the sub was large enough to trip
# JVM bytecode verification.

# --- Test 1: simple short-circuit my-assignment -----------------------
# Ensure `my $h_new` inside `and defined((my $h_new = ...)->{k})` works.
sub check_short_circuit {
    my $arg = shift;
    if ( ref($arg)
         and UNIVERSAL::isa($arg, 'HASH')
         and defined( (my $h_new = $arg)->{key} ) )
    {
        return $h_new->{key};
    }
    return 'no-match';
}

print "not " unless check_short_circuit(undef)           eq 'no-match';
print "ok 1 - skipped my-assignment via ref() short-circuit\n";

print "not " unless check_short_circuit('plain-string')  eq 'no-match';
print "ok 2 - skipped my-assignment via isa() short-circuit\n";

print "not " unless check_short_circuit({ key => 'OK' }) eq 'OK';
print "ok 3 - reached my-assignment and read the value\n";

# --- Test 2: eval'd sub with large body + same pattern ----------------
# This shape matches DBI::PurePerl's dispatch wrapper, which is the
# real-world case that first surfaced the bug. Building the sub via
# eval STRING forces the interpreter path in many PerlOnJava modes.
my $eval_code = q{
    sub {
        my @ret = @_;
        # Dummy temp-register-consuming statement before the my-decl:
        my $prev = join " ", map { "x$_" } @ret;
        if ( ref $ret[0]
             and UNIVERSAL::isa($ret[0], 'HASH')
             and defined( (my $h_new = $ret[0])->{key} ) )
        {
            return "found:" . $h_new->{key};
        }
        return "fallback:$prev";
    }
};
my $sub = eval $eval_code;
die $@ if $@;

print "not " unless $sub->({ key => 'yes' }, 'ignored') eq 'found:yes';
print "ok 4 - eval'd sub found key in hashref\n";

print "not " unless $sub->('str', 'ignored') =~ /^fallback:/;
print "ok 5 - eval'd sub took fallback path without crashing\n";

# --- Test 3: scope-exit cleanup safe even with stale register ---------
# Call the sub repeatedly so any lingering register reuse across
# invocations is exercised.
my $ok = 1;
for my $i (1..100) {
    my $r = $sub->($i % 2 ? { key => $i } : "plain-$i");
    $ok = 0 unless defined $r && length $r;
}
print "not " unless $ok;
print "ok 6 - 100 iterations without scope-exit crash\n";
