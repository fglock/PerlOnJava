use strict;
use warnings;
use Test::More;

# =============================================================================
# destroy_return.t — DESTROY across function return boundaries
#
# Tests the critical case from the design doc: objects returned from functions
# must NOT be prematurely destroyed. This is where naive scope-based DESTROY
# (PR #450) failed.
# =============================================================================

# --- Single-boundary return: sub creates and returns object ---
{
    my @log;
    {
        package DR_Single;
        sub new     { bless { id => $_[1] }, $_[0] }
        sub DESTROY { push @log, "destroyed:" . $_[0]->{id} }
    }
    sub dr_make_single { my $obj = DR_Single->new("s1"); return $obj }
    my $x = dr_make_single();
    is_deeply(\@log, [], "returned object not destroyed after single-boundary return");
    is($x->{id}, "s1", "returned object has correct data");
    undef $x;
    is_deeply(\@log, ["destroyed:s1"], "destroyed when caller drops ref");
}

# --- Two-boundary return: helper wraps constructor ---
{
    my @log;
    {
        package DR_Two;
        sub new     { bless { id => $_[1] }, $_[0] }
        sub DESTROY { push @log, "destroyed:" . $_[0]->{id} }
    }
    sub dr_inner { return DR_Two->new("t1") }
    sub dr_outer { return dr_inner() }
    my $x = dr_outer();
    is_deeply(\@log, [], "not destroyed after two-boundary return");
    is($x->{id}, "t1", "returned object has correct data");
    undef $x;
    is_deeply(\@log, ["destroyed:t1"], "destroyed when caller drops ref");
}

# --- Three-boundary return ---
{
    my @log;
    {
        package DR_Three;
        sub new     { bless { id => $_[1] }, $_[0] }
        sub DESTROY { push @log, "destroyed:" . $_[0]->{id} }
    }
    sub dr_level3 { return DR_Three->new("l3") }
    sub dr_level2 { return dr_level3() }
    sub dr_level1 { return dr_level2() }
    my $x = dr_level1();
    is_deeply(\@log, [], "not destroyed after three-boundary return");
    undef $x;
    is_deeply(\@log, ["destroyed:l3"], "destroyed when caller drops ref");
}

# --- Return without explicit 'return' keyword ---
{
    my @log;
    {
        package DR_Implicit;
        sub new     { bless {}, shift }
        sub DESTROY { push @log, "destroyed" }
    }
    sub dr_implicit { my $obj = DR_Implicit->new; $obj }  # no 'return'
    my $x = dr_implicit();
    is_deeply(\@log, [], "implicit return doesn't destroy object");
    undef $x;
    is_deeply(\@log, ["destroyed"], "destroyed on undef");
}

# --- Return in list context ---
{
    my @log;
    {
        package DR_ListCtx;
        sub new     { bless { n => $_[1] }, $_[0] }
        sub DESTROY { push @log, "d:" . $_[0]->{n} }
    }
    sub dr_list { return (DR_ListCtx->new("a"), DR_ListCtx->new("b")) }
    my @objs = dr_list();
    is_deeply(\@log, [], "list-returned objects not destroyed");
    is(scalar @objs, 2, "got two objects");
    @objs = ();
    my %seen = map { $_ => 1 } @log;
    ok($seen{"d:a"} && $seen{"d:b"}, "both objects destroyed when array cleared");
}

# --- Return via ternary ---
{
    my @log;
    {
        package DR_Ternary;
        sub new     { bless { id => $_[1] }, $_[0] }
        sub DESTROY { push @log, "d:" . $_[0]->{id} }
    }
    sub dr_ternary {
        my $flag = shift;
        return $flag ? DR_Ternary->new("yes") : DR_Ternary->new("no");
    }
    my $x = dr_ternary(1);
    is_deeply(\@log, [], "ternary-returned object alive");
    is($x->{id}, "yes", "got correct branch");
    undef $x;
    is_deeply(\@log, ["d:yes"], "destroyed on undef");
}

# --- Constructor returns object, caller ignores it (void context) ---
{
    my @log;
    {
        package DR_Void;
        sub new     { bless {}, shift }
        sub DESTROY { push @log, "void_destroyed" }
    }
    sub dr_make_void { return DR_Void->new }
    dr_make_void();  # result discarded
    # Object should be destroyed since nobody holds a reference
    is_deeply(\@log, ["void_destroyed"],
        "discarded return value is destroyed");
}

# --- Return and store in hash ---
{
    my @log;
    {
        package DR_HashStore;
        sub new     { bless { id => $_[1] }, $_[0] }
        sub DESTROY { push @log, "d:" . $_[0]->{id} }
    }
    sub dr_for_hash { return DR_HashStore->new("h1") }
    my %h;
    $h{obj} = dr_for_hash();
    is_deeply(\@log, [], "object stored in hash is alive");
    delete $h{obj};
    is_deeply(\@log, ["d:h1"], "destroyed on hash delete");
}

# --- Return and store in array ---
{
    my @log;
    {
        package DR_ArrayStore;
        sub new     { bless { id => $_[1] }, $_[0] }
        sub DESTROY { push @log, "d:" . $_[0]->{id} }
    }
    sub dr_for_array { return DR_ArrayStore->new("a1") }
    my @arr;
    push @arr, dr_for_array();
    is_deeply(\@log, [], "object stored in array is alive");
    @arr = ();
    is_deeply(\@log, ["d:a1"], "destroyed when array cleared");
}

# --- Method chaining through return ---
{
    my @log;
    {
        package DR_Chain;
        sub new     { bless { val => 0 }, shift }
        sub inc     { $_[0]->{val}++; $_[0] }
        sub val     { $_[0]->{val} }
        sub DESTROY { push @log, "chain_destroyed:" . $_[0]->{val} }
    }
    sub dr_chain_make { DR_Chain->new->inc->inc->inc }
    my $x = dr_chain_make();
    is($x->val, 3, "method chaining preserved through return");
    is_deeply(\@log, [], "chained object not destroyed");
    undef $x;
    is_deeply(\@log, ["chain_destroyed:3"], "destroyed with final state");
}

done_testing();
