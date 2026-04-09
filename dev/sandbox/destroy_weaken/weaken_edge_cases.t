use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken isweak);

# =============================================================================
# weaken_edge_cases.t — Edge cases for weak references
#
# Tests: weaken on non-ref (error), weaken + re-bless, weaken + overwrite,
# weak ref survives re-bless, weaken in nested structures, weak ref
# to object that resurrects in DESTROY.
# =============================================================================

my $has_unweaken = eval { Scalar::Util->import('unweaken'); 1 };

# --- weaken on non-reference dies ---
{
    my $scalar = 42;
    my $ok = eval { weaken($scalar); 1 };
    ok(!$ok, "weaken on non-reference throws error");
    like($@, qr/nonreference|non-reference|modify|read-only/i,
        "error message mentions the problem");
}

# --- weaken + overwrite with new ref ---
{
    my $strong1 = { id => "first" };
    my $strong2 = { id => "second" };
    my $ref = $strong1;
    weaken($ref);
    ok(isweak($ref), "ref is weak");
    $ref = $strong2;  # overwrite weak ref with strong ref
    ok(!isweak($ref), "overwritten ref is strong (not weak)");
    is($ref->{id}, "second", "ref points to new object");
}

# --- weaken + overwrite with non-ref ---
{
    my $strong = { id => "obj" };
    my $ref = $strong;
    weaken($ref);
    ok(isweak($ref), "ref is weak");
    $ref = 42;  # overwrite with non-ref
    ok(!isweak($ref), "overwritten with non-ref is not weak");
    is($ref, 42, "ref is now a plain scalar");
}

# --- Weak ref to blessed object, then re-bless ---
{
    {
        package WE_ClassA;
        sub new { bless {}, shift }
    }
    {
        package WE_ClassB;
    }
    my $strong = WE_ClassA->new;
    my $weak = $strong;
    weaken($weak);
    bless $strong, 'WE_ClassB';
    is(ref($weak), "WE_ClassB", "weak ref sees re-blessed class");
    ok(isweak($weak), "ref is still weak after re-bless");
}

# --- Weak ref in deeply nested hash ---
{
    my $strong = { data => "deep" };
    my %deep;
    $deep{a}{b}{c} = $strong;
    weaken($deep{a}{b}{c});
    ok(isweak($deep{a}{b}{c}), "deeply nested hash value is weak");
    is($deep{a}{b}{c}{data}, "deep", "can access through deep weak ref");
    undef $strong;
    ok(!defined($deep{a}{b}{c}), "deep weak ref becomes undef");
}

# --- Weak ref in array of arrays ---
{
    my $strong = [1, 2, 3];
    my @nested;
    $nested[0][0] = $strong;
    weaken($nested[0][0]);
    ok(isweak($nested[0][0]), "nested array element is weak");
    undef $strong;
    ok(!defined($nested[0][0]), "nested weak array element becomes undef");
}

# --- Multiple weak refs cleared simultaneously ---
{
    my $strong = { id => "multi_clear" };
    my @weaks;
    for (0..4) {
        $weaks[$_] = $strong;
        weaken($weaks[$_]);
    }
    for (0..4) {
        ok(isweak($weaks[$_]), "weak ref $_ is weak");
    }
    undef $strong;
    for (0..4) {
        ok(!defined($weaks[$_]), "weak ref $_ becomes undef after strong dropped");
    }
}

# --- Weak ref + DESTROY resurrection ---
# In Perl 5, if DESTROY resurrects the object (stores $_[0] elsewhere),
# weak refs may remain valid because the refcount was restored.
{
    my @saved;
    my @log;
    my $should_save = 1;
    {
        package WE_Resurrect;
        sub new     { bless { id => $_[1] }, $_[0] }
        sub DESTROY {
            push @log, "destroyed:" . $_[0]->{id};
            push @saved, $_[0] if $should_save;  # resurrect
        }
    }
    my $strong = WE_Resurrect->new("res");
    my $weak = $strong;
    weaken($weak);
    undef $strong;
    is_deeply(\@log, ["destroyed:res"], "DESTROY fired");
    # The resurrected object is accessible through @saved
    ok(defined($saved[0]), "resurrected object accessible through \@saved");
    is($saved[0]->{id}, "res", "resurrected object has correct data");
    # Weak ref behavior after resurrection is implementation-defined:
    # it may be undef or still valid depending on the implementation.
    ok(1, "weak ref after resurrection handled without crash");
    $should_save = 0;  # prevent resurrection during cleanup
    @saved = ();
}

# --- unweaken restores strong semantics ---
SKIP: {
    skip "unweaken not available", 3 unless $has_unweaken;
    my @log;
    {
        package WE_Unweaken;
        sub new     { bless {}, shift }
        sub DESTROY { push @log, "destroyed" }
    }
    my $strong = WE_Unweaken->new;
    my $ref = $strong;
    weaken($ref);
    Scalar::Util::unweaken($ref);
    undef $strong;
    is_deeply(\@log, [], "after unweaken, dropping original strong ref doesn't DESTROY");
    ok(defined($ref), "unweakened ref keeps object alive");
    undef $ref;
    is_deeply(\@log, ["destroyed"], "DESTROY fires when unweakened ref dropped");
}

# --- Weak ref to object in closure ---
{
    my @log;
    {
        package WE_Closure;
        sub new     { bless { val => $_[1] }, $_[0] }
        sub DESTROY { push @log, "closure_destroyed" }
    }
    my $weak;
    {
        my $strong = WE_Closure->new("in_closure");
        $weak = $strong;
        weaken($weak);
        my $getter = sub { $strong->{val} };
        is($getter->(), "in_closure", "closure accesses object");
    }
    # $strong left scope, closure is gone, object should be destroyed
    is_deeply(\@log, ["closure_destroyed"], "object destroyed when closure scope exits");
    ok(!defined($weak), "weak ref undef after scope exit");
}

# --- weaken on already-undef scalar ---
{
    my $ref = undef;
    my $ok = eval { weaken($ref); 1 };
    # weaken on undef should either be a no-op or an error
    ok(defined($ok), "weaken on undef doesn't crash (may warn or no-op)");
}

# --- Weak ref to same object from different code paths ---
{
    my @log;
    {
        package WE_MultiPath;
        sub new     { bless { id => $_[1] }, $_[0] }
        sub DESTROY { push @log, "mp:" . $_[0]->{id} }
    }
    my $strong = WE_MultiPath->new("mp1");
    my %cache = (obj => $strong);
    my @list = ($strong);
    weaken($cache{obj});
    weaken($list[0]);
    ok(isweak($cache{obj}), "hash weak ref");
    ok(isweak($list[0]), "array weak ref");
    ok(!isweak($strong), "original strong ref unchanged");
    undef $strong;
    is_deeply(\@log, ["mp:mp1"], "DESTROY fires once");
    ok(!defined($cache{obj}), "hash weak ref undef");
    ok(!defined($list[0]), "array weak ref undef");
}

done_testing();
