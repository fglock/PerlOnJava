use strict;
use warnings;
use Test::More;

# =============================================================================
# destroy_glob_undef.t — DESTROY when typeglobs are undef'd
#
# Tests that `undef *Pkg::name` fires DESTROY on the blessed values held by
# the SCALAR / ARRAY / HASH slots of that typeglob. Real Perl fires these
# immediately (matched here). For whole-stash undef (`undef *Pkg::` /
# `undef %Pkg::`) real Perl defers to global cleanup; we don't assert
# immediate DESTROY in that case.
# =============================================================================

our @log;

{
    package DGU_Thing;
    sub new     { bless { id => $_[1] }, $_[0] }
    sub DESTROY { push @main::log, "d:" . $_[0]->{id} }
}

# --- undef *Pkg::scalar fires DESTROY for the scalar value ---
{
    @log = ();
    $DGU_PkgS::obj = DGU_Thing->new("s1");
    is_deeply(\@log, [], "scalar still alive");
    undef *DGU_PkgS::obj;
    is_deeply(\@log, ["d:s1"], "DESTROY fires on undef *Pkg::scalar");
}

# --- undef *Pkg::array fires DESTROY for the array elements ---
{
    @log = ();
    @DGU_PkgA::arr = (DGU_Thing->new("a1"), DGU_Thing->new("a2"));
    is_deeply(\@log, [], "array elements still alive");
    undef *DGU_PkgA::arr;
    my %seen = map { $_ => 1 } @log;
    ok($seen{"d:a1"} && $seen{"d:a2"},
        "DESTROY fires on undef *Pkg::array for all elements")
        or diag("log=[@log]");
}

# --- undef *Pkg::hash fires DESTROY for the hash values ---
{
    @log = ();
    %DGU_PkgH::hsh = (k1 => DGU_Thing->new("h1"), k2 => DGU_Thing->new("h2"));
    is_deeply(\@log, [], "hash values still alive");
    undef *DGU_PkgH::hsh;
    my %seen = map { $_ => 1 } @log;
    ok($seen{"d:h1"} && $seen{"d:h2"},
        "DESTROY fires on undef *Pkg::hash for all values")
        or diag("log=[@log]");
}

# --- undef *Pkg::mixed fires DESTROY for all three slots at once ---
{
    @log = ();
    $DGU_PkgM::x = DGU_Thing->new("mS");
    @DGU_PkgM::x = (DGU_Thing->new("mA"));
    %DGU_PkgM::x = (k => DGU_Thing->new("mH"));
    is_deeply(\@log, [], "all three slots alive");
    undef *DGU_PkgM::x;
    my %seen = map { $_ => 1 } @log;
    ok($seen{"d:mS"} && $seen{"d:mA"} && $seen{"d:mH"},
        "DESTROY fires for SCALAR/ARRAY/HASH slots of a single glob")
        or diag("log=[@log]");
}

done_testing;
