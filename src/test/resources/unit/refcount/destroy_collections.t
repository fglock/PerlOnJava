use strict;
use warnings;
use Test::More;

# =============================================================================
# destroy_collections.t — DESTROY for blessed refs inside collections
#
# Tests blessed objects stored in arrays, hashes, nested structures, and
# various collection operations (splice, shift, pop, clear, etc.).
# =============================================================================

# --- Blessed ref in array, destroyed on clear ---
{
    my @log;
    {
        package DC_ArrClear;
        sub new     { bless { id => $_[1] }, $_[0] }
        sub DESTROY { push @log, "d:" . $_[0]->{id} }
    }
    my @arr = (DC_ArrClear->new("a"), DC_ArrClear->new("b"), DC_ArrClear->new("c"));
    is_deeply(\@log, [], "objects alive in array");
    @arr = ();
    my %seen = map { $_ => 1 } @log;
    ok($seen{"d:a"} && $seen{"d:b"} && $seen{"d:c"},
        "all objects destroyed on array clear");
}

# --- Blessed ref removed via pop ---
{
    my @log;
    {
        package DC_Pop;
        sub new     { bless { id => $_[1] }, $_[0] }
        sub DESTROY { push @log, "d:" . $_[0]->{id} }
    }
    my @arr;
    push @arr, DC_Pop->new("p1"), DC_Pop->new("p2");
    my $popped = pop @arr;
    is_deeply(\@log, [], "popped object still alive (held by \$popped)");
    undef $popped;
    is_deeply(\@log, ["d:p2"], "destroyed after popped ref dropped");
}

# --- Blessed ref removed via shift ---
{
    my @log;
    {
        package DC_Shift;
        sub new     { bless { id => $_[1] }, $_[0] }
        sub DESTROY { push @log, "d:" . $_[0]->{id} }
    }
    my @arr;
    push @arr, DC_Shift->new("s1"), DC_Shift->new("s2");
    my $shifted = shift @arr;
    is_deeply(\@log, [], "shifted object still alive");
    undef $shifted;
    is_deeply(\@log, ["d:s1"], "destroyed after shifted ref dropped");
}

# --- Blessed ref removed via splice ---
{
    my @log;
    {
        package DC_Splice;
        sub new     { bless { id => $_[1] }, $_[0] }
        sub DESTROY { push @log, "d:" . $_[0]->{id} }
    }
    my @arr = (DC_Splice->new("x"), DC_Splice->new("y"), DC_Splice->new("z"));
    my @removed = splice(@arr, 1, 1);  # remove "y"
    is_deeply(\@log, [], "spliced object alive (in \@removed)");
    @removed = ();
    is_deeply(\@log, ["d:y"], "destroyed after splice result cleared");
}

# --- Hash clear destroys all values ---
{
    my @log;
    {
        package DC_HashClear;
        sub new     { bless { id => $_[1] }, $_[0] }
        sub DESTROY { push @log, "d:" . $_[0]->{id} }
    }
    my %h = (a => DC_HashClear->new("ha"), b => DC_HashClear->new("hb"));
    %h = ();
    my %seen = map { $_ => 1 } @log;
    ok($seen{"d:ha"} && $seen{"d:hb"}, "all hash values destroyed on clear");
}

# --- Nested structure: hash of arrays of objects ---
{
    my @log;
    {
        package DC_Nested;
        sub new     { bless { id => $_[1] }, $_[0] }
        sub DESTROY { push @log, "d:" . $_[0]->{id} }
    }
    {
        my %data;
        $data{list} = [DC_Nested->new("n1"), DC_Nested->new("n2")];
        is_deeply(\@log, [], "nested objects alive");
    }
    my %seen = map { $_ => 1 } @log;
    ok($seen{"d:n1"} && $seen{"d:n2"},
        "nested objects destroyed when outer hash goes out of scope");
}

# --- Object stored in two collections, only destroyed when both drop it ---
{
    my @log;
    {
        package DC_SharedRef;
        sub new     { bless { id => $_[1] }, $_[0] }
        sub DESTROY { push @log, "d:" . $_[0]->{id} }
    }
    my $obj = DC_SharedRef->new("shared");
    my @arr = ($obj);
    my %h = (key => $obj);
    undef $obj;
    is_deeply(\@log, [], "object alive (in array and hash)");
    @arr = ();
    is_deeply(\@log, [], "object alive (still in hash)");
    %h = ();
    is_deeply(\@log, ["d:shared"], "destroyed when last collection drops it");
}

# --- Blessed ref as hash value, overwritten ---
{
    my @log;
    {
        package DC_HashOverwrite;
        sub new     { bless { id => $_[1] }, $_[0] }
        sub DESTROY { push @log, "d:" . $_[0]->{id} }
    }
    my %h;
    $h{key} = DC_HashOverwrite->new("old");
    $h{key} = DC_HashOverwrite->new("new");
    is_deeply(\@log, ["d:old"], "old hash value destroyed on overwrite");
    delete $h{key};
    is_deeply(\@log, ["d:old", "d:new"], "new value destroyed on delete");
}

# --- Array of objects going out of scope ---
{
    my @log;
    {
        package DC_ArrScope;
        sub new     { bless { id => $_[1] }, $_[0] }
        sub DESTROY { push @log, "d:" . $_[0]->{id} }
    }
    {
        my @arr;
        for my $i (1..3) {
            push @arr, DC_ArrScope->new("item$i");
        }
        is_deeply(\@log, [], "objects alive inside scope");
    }
    is(scalar @log, 3, "all 3 objects destroyed at scope exit");
}

# --- Object inside closure ---
{
    my @log;
    {
        package DC_Closure;
        sub new     { bless { id => $_[1] }, $_[0] }
        sub DESTROY { push @log, "d:" . $_[0]->{id} }
    }
    my $code;
    {
        my $obj = DC_Closure->new("closure");
        $code = sub { return $obj->{id} };
        is($code->(), "closure", "closure can access object");
    }
    is_deeply(\@log, [], "object alive while closure exists");
    is($code->(), "closure", "closure still works");
    undef $code;
    is_deeply(\@log, ["d:closure"], "destroyed when closure dropped");
}

done_testing();
