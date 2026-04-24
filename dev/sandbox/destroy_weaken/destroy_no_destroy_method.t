use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken isweak);

# =============================================================================
# destroy_no_destroy_method.t — Cascading cleanup for blessed objects
# without a DESTROY method
#
# When a blessed hash goes out of scope and its class does NOT define
# DESTROY, Perl must still decrement refcounts on the hash's values.
# This is critical for patterns like DBIx::Class where intermediate
# Moo objects (e.g. BlockRunner) hold strong refs to tracked objects
# but don't define DESTROY themselves.
#
# Root cause: DestroyDispatch.callDestroy skips scopeExitCleanupHash
# for blessed objects whose class has no DESTROY method, leaking the
# refcounts of the hash's values.
# =============================================================================

# --- Blessed holder WITHOUT DESTROY should still release contents ---
{
    my @log;
    {
        package NDM_Tracked;
        sub new     { bless {}, shift }
        sub DESTROY { push @log, "tracked" }
    }
    {
        package NDM_HolderNoDestroy;
        sub new { bless { target => $_[1] }, $_[0] }
        # No DESTROY defined
    }
    my $weak;
    {
        my $tracked = NDM_Tracked->new;
        $weak = $tracked;
        weaken($weak);
        my $holder = NDM_HolderNoDestroy->new($tracked);
    }
    is_deeply(\@log, ["tracked"],
        "blessed holder without DESTROY still triggers DESTROY on contents");
    ok(!defined $weak,
        "tracked object is collected when holder without DESTROY goes out of scope");
}

# --- Contrast: blessed holder WITH DESTROY properly releases contents ---
{
    my @log;
    {
        package NDM_TrackedB;
        sub new     { bless {}, shift }
        sub DESTROY { push @log, "tracked" }
    }
    {
        package NDM_HolderWithDestroy;
        sub new     { bless { target => $_[1] }, $_[0] }
        sub DESTROY { push @log, "holder" }
    }
    my $weak;
    {
        my $tracked = NDM_TrackedB->new;
        $weak = $tracked;
        weaken($weak);
        my $holder = NDM_HolderWithDestroy->new($tracked);
    }
    is_deeply(\@log, ["holder", "tracked"],
        "blessed holder with DESTROY cascades to contents");
    ok(!defined $weak,
        "tracked object is collected when holder with DESTROY goes out of scope");
}

# --- Contrast: unblessed hashref properly releases contents ---
{
    my @log;
    {
        package NDM_TrackedC;
        sub new     { bless {}, shift }
        sub DESTROY { push @log, "tracked" }
    }
    my $weak;
    {
        my $tracked = NDM_TrackedC->new;
        $weak = $tracked;
        weaken($weak);
        my $holder = { target => $tracked };
    }
    is_deeply(\@log, ["tracked"],
        "unblessed hashref releases tracked contents");
    ok(!defined $weak,
        "tracked object is collected when unblessed holder goes out of scope");
}

# --- Nested: blessed-no-DESTROY holds blessed-no-DESTROY holds tracked ---
{
    my @log;
    {
        package NDM_TrackedD;
        sub new     { bless {}, shift }
        sub DESTROY { push @log, "tracked" }
    }
    {
        package NDM_OuterNoDestroy;
        sub new { bless { inner => $_[1] }, $_[0] }
    }
    {
        package NDM_InnerNoDestroy;
        sub new { bless { target => $_[1] }, $_[0] }
    }
    my $weak;
    {
        my $tracked = NDM_TrackedD->new;
        $weak = $tracked;
        weaken($weak);
        my $inner = NDM_InnerNoDestroy->new($tracked);
        my $outer = NDM_OuterNoDestroy->new($inner);
    }
    ok(!defined $weak,
        "nested blessed-no-DESTROY chain still releases tracked object");
}

# --- Weak backref pattern (Schema/Storage cycle) ---
#
# Schema (blessed, has DESTROY) ──strong──> Storage
# Storage (blessed, has DESTROY) ──weak────> Schema
# BlockRunner (blessed, NO DESTROY) ──strong──> Storage
#
# When BlockRunner goes out of scope, Storage refcount must decrement.
# Later when Schema goes out of scope, cascading DESTROY must bring
# Storage refcount to 0.
{
    my @log;
    {
        package NDM_Storage;
        use Scalar::Util qw(weaken);
        sub new {
            my ($class, $schema) = @_;
            my $self = bless {}, $class;
            $self->{schema} = $schema;
            weaken($self->{schema});
            return $self;
        }
        sub DESTROY { push @log, "storage" }
    }
    {
        package NDM_Schema;
        sub new     { bless {}, $_[0] }
        sub DESTROY { push @log, "schema" }
    }
    {
        package NDM_BlockRunner;
        sub new { bless { storage => $_[1] }, $_[0] }
        # No DESTROY — like DBIx::Class::Storage::BlockRunner
    }

    my $weak_storage;
    {
        my $schema = NDM_Schema->new;
        my $storage = NDM_Storage->new($schema);
        $schema->{storage} = $storage;

        $weak_storage = $storage;
        weaken($weak_storage);

        # Simulate dbh_do: create a BlockRunner that holds storage
        my $runner = NDM_BlockRunner->new($storage);
        undef $storage;

        # Runner goes out of scope here — must release storage ref
        undef $runner;
        # Now only $schema->{storage} should hold storage
    }
    # After block: schema out of scope -> DESTROY schema -> cascade -> DESTROY storage
    ok(!defined $weak_storage,
        "Schema/Storage/BlockRunner pattern: storage collected after all go out of scope");
    my @sorted = sort @log;
    ok(grep({ $_ eq "schema" } @sorted) && grep({ $_ eq "storage" } @sorted),
        "both schema and storage DESTROY fired");
}

# --- Explicit undef of blessed-no-DESTROY should release contents ---
{
    my @log;
    {
        package NDM_TrackedE;
        sub new     { bless {}, shift }
        sub DESTROY { push @log, "tracked" }
    }
    {
        package NDM_HolderNoDestroyE;
        sub new { bless { target => $_[1] }, $_[0] }
    }
    my $weak;
    my $tracked = NDM_TrackedE->new;
    $weak = $tracked;
    weaken($weak);
    my $holder = NDM_HolderNoDestroyE->new($tracked);
    undef $tracked;  # only holder keeps it alive
    ok(defined $weak, "tracked still alive via holder");
    undef $holder;   # should cascade-release tracked
    ok(!defined $weak,
        "explicit undef of blessed-no-DESTROY holder releases tracked object");
    is_deeply(\@log, ["tracked"], "DESTROY fired on tracked after holder undef");
}

# --- Array-based blessed object without DESTROY ---
{
    my @log;
    {
        package NDM_TrackedF;
        sub new     { bless {}, shift }
        sub DESTROY { push @log, "tracked" }
    }
    {
        package NDM_ArrayHolder;
        sub new { bless [ $_[1] ], $_[0] }
        # No DESTROY
    }
    my $weak;
    {
        my $tracked = NDM_TrackedF->new;
        $weak = $tracked;
        weaken($weak);
        my $holder = NDM_ArrayHolder->new($tracked);
    }
    ok(!defined $weak,
        "array-based blessed-no-DESTROY releases tracked object");
}

done_testing;
