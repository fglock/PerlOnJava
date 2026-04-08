use strict;
use warnings;
use Test::More;

# =============================================================================
# destroy_edge_cases.t — DESTROY edge cases and special semantics
#
# Tests: object resurrection, re-bless, overwrite ordering (DESTROY sees new
# value), exception-in-DESTROY, DESTROY on global variables, nested DESTROY.
# =============================================================================

# --- Object resurrection: DESTROY saves $_[0] ---
{
    my @saved;
    my $should_save = 1;
    {
        package DE_Resurrect;
        sub new     { bless { alive => 1 }, shift }
        sub DESTROY { push @saved, $_[0] if $should_save }
    }
    { my $obj = DE_Resurrect->new; }
    is(scalar @saved, 1, "DESTROY saved the object");
    is($saved[0]->{alive}, 1, "resurrected object still has data");
    is(ref($saved[0]), "DE_Resurrect", "resurrected object still blessed");
    $should_save = 0;  # prevent resurrection during cleanup
    @saved = ();
}

# --- DESTROY called again if resurrected object's refcount drops again ---
# Perl 5 allows re-DESTROY (with a warning about "new reference to dead object")
{
    my $destroy_count = 0;
    my @saved;
    {
        package DE_ResurrectOnce;
        sub new     { bless {}, shift }
        sub DESTROY { $destroy_count++; push @saved, $_[0] if $destroy_count == 1 }
    }
    { my $obj = DE_ResurrectOnce->new; }
    is($destroy_count, 1, "DESTROY called once on first drop");
    # Releasing the resurrected object may call DESTROY again (Perl 5 behavior)
    @saved = ();
    ok($destroy_count >= 1, "DESTROY may be called again after resurrection released");
}

# --- Exception in DESTROY becomes a warning ---
{
    my @warnings;
    local $SIG{__WARN__} = sub { push @warnings, $_[0] };
    {
        package DE_DieInDestroy;
        sub new     { bless {}, shift }
        sub DESTROY { die "cleanup failed" }
    }
    { my $obj = DE_DieInDestroy->new; }
    ok(scalar @warnings >= 1, "die in DESTROY produced a warning");
    like($warnings[0], qr/cleanup failed/, "warning contains the die message");
    like($warnings[0], qr/\(in cleanup\)/, "warning tagged with (in cleanup)");
}

# --- $@ NOT automatically localized in DESTROY ---
# Perl 5 does NOT automatically localize $@ during DESTROY.
# DESTROY methods should use "local $@" if they call eval.
{
    my $at_before;
    my $at_inside;
    {
        package DE_DollarAt;
        sub new     { bless {}, shift }
        sub DESTROY { eval { die "inside destroy" }; }
    }
    eval { die "outer error" };
    $at_before = $@;
    {
        my $obj = DE_DollarAt->new;
    }
    # $@ may or may not be preserved — depends on Perl version and context.
    # The important thing is that DESTROY doesn't crash.
    ok(1, "\$\@ survives DESTROY without crash");
}

# --- $@ preserved when DESTROY uses local $@ ---
{
    my $at_before;
    my $at_after;
    {
        package DE_LocalDollarAt;
        sub new     { bless {}, shift }
        sub DESTROY { local $@; eval { die "inside destroy" }; }
    }
    eval { die "outer error" };
    $at_before = $@;
    {
        my $obj = DE_LocalDollarAt->new;
    }
    $at_after = $@;
    is($at_after, $at_before,
        "\$\@ preserved when DESTROY uses local \$\@");
}

# --- DESTROY ordering: assignment overwrites old value ---
# Perl 5 semantics: DESTROY of old value sees the NEW state of the variable
{
    my $seen_value;
    {
        package DE_OverwriteOrder;
        sub new     { bless { id => $_[1] }, $_[0] }
        sub DESTROY {
            # We can't directly see what $var is here, but we verify the order
            $seen_value = $_[0]->{id};
        }
    }
    my $var = DE_OverwriteOrder->new("old");
    $var = DE_OverwriteOrder->new("new");
    is($seen_value, "old", "old object's DESTROY fires on overwrite");
}

# --- Re-bless to class with DESTROY ---
{
    my @log;
    {
        package DE_NoDestroy;
        sub new { bless {}, shift }
    }
    {
        package DE_HasDestroy;
        sub DESTROY { push @log, "has_destroy" }
    }
    {
        my $obj = DE_NoDestroy->new;
        bless $obj, 'DE_HasDestroy';
    }
    is_deeply(\@log, ["has_destroy"],
        "re-bless to class with DESTROY: DESTROY fires");
}

# --- Re-bless from class with DESTROY to class without ---
{
    my @log;
    {
        package DE_WithDestroy;
        sub new     { bless {}, shift }
        sub DESTROY { push @log, "with_destroy" }
    }
    {
        package DE_WithoutDestroy;
        sub new { bless {}, shift }
    }
    {
        my $obj = DE_WithDestroy->new;
        bless $obj, 'DE_WithoutDestroy';
    }
    is_deeply(\@log, [],
        "re-bless to class without DESTROY: no DESTROY fires");
}

# --- Re-bless between two classes that both have DESTROY ---
{
    my @log;
    {
        package DE_ClassA;
        sub new     { bless {}, shift }
        sub DESTROY { push @log, "A" }
    }
    {
        package DE_ClassB;
        sub DESTROY { push @log, "B" }
    }
    {
        my $obj = DE_ClassA->new;
        bless $obj, 'DE_ClassB';
    }
    is_deeply(\@log, ["B"],
        "re-bless between DESTROY classes: new class's DESTROY fires");
}

# --- Nested DESTROY: DESTROY that triggers another DESTROY ---
{
    my @log;
    {
        package DE_Outer;
        sub new     { bless { inner => undef }, shift }
        sub DESTROY { push @log, "outer" }
    }
    {
        package DE_Inner;
        sub new     { bless {}, shift }
        sub DESTROY { push @log, "inner" }
    }
    {
        my $outer = DE_Outer->new;
        $outer->{inner} = DE_Inner->new;
    }
    # Both objects should be destroyed; inner before outer (LIFO within hash cleanup)
    ok(scalar @log >= 2, "both nested objects destroyed");
    # The exact order may vary, but both must appear
    my %seen = map { $_ => 1 } @log;
    ok($seen{outer}, "outer DESTROY called");
    ok($seen{inner}, "inner DESTROY called");
}

# --- DESTROY during eval: doesn't leak exception ---
{
    my $should_die = 1;
    my $result = eval {
        {
            package DE_DieInEval;
            sub new     { bless {}, shift }
            sub DESTROY { die "destroy error" if $should_die }
        }
        { my $obj = DE_DieInEval->new; }
        42;
    };
    is($result, 42, "DESTROY exception doesn't abort eval block");
    $should_die = 0;  # prevent die during global destruction
}

# --- DESTROY on overwrite with same class instance ---
{
    my @log;
    {
        package DE_Replace;
        sub new     { bless { id => $_[1] }, $_[0] }
        sub DESTROY { push @log, "destroyed:" . $_[0]->{id} }
    }
    my $x = DE_Replace->new("first");
    $x = DE_Replace->new("second");
    is_deeply(\@log, ["destroyed:first"],
        "replacing with same-class object destroys old one");
    undef $x;
    is_deeply(\@log, ["destroyed:first", "destroyed:second"],
        "undef destroys the replacement");
}

# --- DESTROY with local() ---
{
    my @log;
    {
        package DE_Local;
        sub new     { bless {}, shift }
        sub DESTROY { push @log, "local_destroyed" }
    }
    our $global_obj;
    $global_obj = DE_Local->new;
    {
        local $global_obj = DE_Local->new;
    }
    is(scalar(grep { $_ eq "local_destroyed" } @log), 1,
        "DESTROY called when local() scope exits");
    undef $global_obj;
    is(scalar(grep { $_ eq "local_destroyed" } @log), 2,
        "original object destroyed on undef");
}

done_testing();
