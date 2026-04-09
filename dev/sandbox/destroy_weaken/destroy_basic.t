use strict;
use warnings;
use Test::More;

# =============================================================================
# destroy_basic.t — Core DESTROY semantics
#
# Tests the fundamental DESTROY contract: called once, at the right time,
# for the right triggers (scope exit, undef, overwrite, hash delete).
# =============================================================================

# --- DESTROY at scope exit ---
{
    my @log;
    {
        package DB_ScopeExit;
        sub new     { bless {}, shift }
        sub DESTROY { push @log, "destroyed" }
    }
    {
        my $obj = DB_ScopeExit->new;
    }
    is_deeply(\@log, ["destroyed"], "DESTROY called when lexical goes out of scope");
}

# --- DESTROY on explicit undef ---
{
    my @log;
    {
        package DB_Undef;
        sub new     { bless {}, shift }
        sub DESTROY { push @log, "destroyed" }
    }
    my $obj = DB_Undef->new;
    is_deeply(\@log, [], "DESTROY not called before undef");
    undef $obj;
    is_deeply(\@log, ["destroyed"], "DESTROY called on undef \$obj");
}

# --- DESTROY on scalar overwrite ---
{
    my @log;
    {
        package DB_Overwrite;
        sub new     { bless {}, shift }
        sub DESTROY { push @log, "destroyed" }
    }
    my $obj = DB_Overwrite->new;
    $obj = 42;
    is_deeply(\@log, ["destroyed"], "DESTROY called when scalar overwritten with non-ref");
}

# --- DESTROY on hash delete ---
{
    my @log;
    {
        package DB_HashDelete;
        sub new     { bless {}, shift }
        sub DESTROY { push @log, "destroyed" }
    }
    my %h;
    $h{obj} = DB_HashDelete->new;
    delete $h{obj};
    is_deeply(\@log, ["destroyed"], "DESTROY called on hash delete");
}

# --- DESTROY on array element overwrite ---
{
    my @log;
    {
        package DB_ArrayOverwrite;
        sub new     { bless {}, shift }
        sub DESTROY { push @log, "destroyed" }
    }
    my @a;
    $a[0] = DB_ArrayOverwrite->new;
    $a[0] = undef;
    is_deeply(\@log, ["destroyed"], "DESTROY called when array element set to undef");
}

# --- Multiple references delay DESTROY ---
{
    my @log;
    {
        package DB_MultiRef;
        sub new     { bless {}, shift }
        sub DESTROY { push @log, "destroyed" }
    }
    my $a = DB_MultiRef->new;
    my $b = $a;
    undef $a;
    is_deeply(\@log, [], "DESTROY not called while second ref exists");
    undef $b;
    is_deeply(\@log, ["destroyed"], "DESTROY called when last ref gone");
}

# --- Three references ---
{
    my @log;
    {
        package DB_ThreeRef;
        sub new     { bless {}, shift }
        sub DESTROY { push @log, "destroyed" }
    }
    my $a = DB_ThreeRef->new;
    my $b = $a;
    my $c = $a;
    undef $a;
    is_deeply(\@log, [], "not destroyed after first undef (2 refs remain)");
    undef $b;
    is_deeply(\@log, [], "not destroyed after second undef (1 ref remains)");
    undef $c;
    is_deeply(\@log, ["destroyed"], "destroyed after last undef");
}

# --- DESTROY called exactly once (scope exit after undef) ---
{
    my $count = 0;
    {
        package DB_Once;
        sub new     { bless {}, shift }
        sub DESTROY { $count++ }
    }
    {
        my $obj = DB_Once->new;
        undef $obj;
    }
    is($count, 1, "DESTROY called exactly once (undef inside scope, then scope exit)");
}

# --- No DESTROY for class without DESTROY method ---
{
    my $destroyed = 0;
    {
        package DB_NoDESTROY;
        sub new { bless {}, shift }
    }
    { my $obj = DB_NoDESTROY->new; }
    is($destroyed, 0, "no DESTROY called for class without DESTROY method");
}

# --- DESTROY receives correct self reference ---
{
    my $self_class;
    {
        package DB_SelfCheck;
        sub new     { bless { id => 42 }, shift }
        sub DESTROY { $self_class = ref($_[0]) . ":" . $_[0]->{id} }
    }
    { my $obj = DB_SelfCheck->new; }
    is($self_class, "DB_SelfCheck:42", "DESTROY receives correct blessed self");
}

# --- DESTROY with blessed array ref ---
{
    my @log;
    {
        package DB_ArrayRef;
        sub new     { bless [1, 2, 3], shift }
        sub DESTROY { push @log, "array_destroyed" }
    }
    { my $obj = DB_ArrayRef->new; }
    is_deeply(\@log, ["array_destroyed"], "DESTROY works for blessed arrayrefs");
}

# --- DESTROY with blessed scalar ref ---
{
    my @log;
    {
        package DB_ScalarRef;
        sub new     { my $x = "hello"; bless \$x, shift }
        sub DESTROY { push @log, "scalar_destroyed" }
    }
    { my $obj = DB_ScalarRef->new; }
    is_deeply(\@log, ["scalar_destroyed"], "DESTROY works for blessed scalar refs");
}

# --- DESTROY ordering: multiple objects in same scope ---
# Note: Perl 5's destruction order for lexicals in the same scope is
# implementation-defined. We only test that both are destroyed.
{
    my @log;
    {
        package DB_Order;
        sub new     { bless { name => $_[1] }, $_[0] }
        sub DESTROY { push @log, $_[0]->{name} }
    }
    {
        my $a = DB_Order->new("first");
        my $b = DB_Order->new("second");
    }
    my %seen = map { $_ => 1 } @log;
    ok($seen{first} && $seen{second},
        "both objects destroyed at scope exit");
    is(scalar @log, 2, "exactly two DESTROY calls");
}

done_testing();
