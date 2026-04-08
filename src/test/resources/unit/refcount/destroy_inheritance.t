use strict;
use warnings;
use Test::More;

# =============================================================================
# destroy_inheritance.t — DESTROY with inheritance, AUTOLOAD, SUPER, UNIVERSAL
#
# Tests: inherited DESTROY, overridden DESTROY, SUPER::DESTROY, AUTOLOAD
# fallback for DESTROY, UNIVERSAL::DESTROY, multiple inheritance (C3 MRO).
# =============================================================================

# --- DESTROY inherited from parent ---
{
    my @log;
    {
        package DI_Parent;
        sub new     { bless {}, shift }
        sub DESTROY { push @log, "parent" }
    }
    {
        package DI_Child;
        our @ISA = ('DI_Parent');
        sub new { bless {}, shift }
    }
    { my $obj = DI_Child->new; }
    is_deeply(\@log, ["parent"], "child inherits parent's DESTROY");
}

# --- Child overrides DESTROY ---
{
    my @log;
    {
        package DI_ParentOverride;
        sub new     { bless {}, shift }
        sub DESTROY { push @log, "parent_override" }
    }
    {
        package DI_ChildOverride;
        our @ISA = ('DI_ParentOverride');
        sub new     { bless {}, shift }
        sub DESTROY { push @log, "child_override" }
    }
    { my $obj = DI_ChildOverride->new; }
    is_deeply(\@log, ["child_override"],
        "child's DESTROY overrides parent's (only child fires)");
}

# --- SUPER::DESTROY from child ---
{
    my @log;
    {
        package DI_ParentSuper;
        sub new     { bless {}, shift }
        sub DESTROY { push @log, "parent_super" }
    }
    {
        package DI_ChildSuper;
        our @ISA = ('DI_ParentSuper');
        sub new     { bless {}, shift }
        sub DESTROY {
            push @log, "child_super";
            $_[0]->SUPER::DESTROY();
        }
    }
    { my $obj = DI_ChildSuper->new; }
    is_deeply(\@log, ["child_super", "parent_super"],
        "SUPER::DESTROY chains to parent");
}

# --- Deep inheritance chain ---
{
    my @log;
    {
        package DI_GrandParent;
        sub new     { bless {}, shift }
        sub DESTROY { push @log, "grandparent" }
    }
    {
        package DI_ParentDeep;
        our @ISA = ('DI_GrandParent');
    }
    {
        package DI_ChildDeep;
        our @ISA = ('DI_ParentDeep');
        sub new { bless {}, shift }
    }
    { my $obj = DI_ChildDeep->new; }
    is_deeply(\@log, ["grandparent"],
        "DESTROY inherited through deep chain (grandparent)");
}

# --- Multiple inheritance: DESTROY from first class in @ISA ---
{
    my @log;
    {
        package DI_MixinA;
        sub DESTROY { push @log, "mixin_a" }
    }
    {
        package DI_MixinB;
        sub DESTROY { push @log, "mixin_b" }
    }
    {
        package DI_MultiChild;
        our @ISA = ('DI_MixinA', 'DI_MixinB');
        sub new { bless {}, shift }
    }
    { my $obj = DI_MultiChild->new; }
    is_deeply(\@log, ["mixin_a"],
        "multiple inheritance: DESTROY from first parent in \@ISA");
}

# --- AUTOLOAD fallback for DESTROY ---
{
    my @log;
    {
        package DI_AutoloadDestroy;
        sub new { bless {}, shift }
        sub AUTOLOAD {
            our $AUTOLOAD;
            if ($AUTOLOAD =~ /::DESTROY$/) {
                push @log, "autoload_destroy";
            }
        }
    }
    { my $obj = DI_AutoloadDestroy->new; }
    is_deeply(\@log, ["autoload_destroy"],
        "AUTOLOAD catches DESTROY when no explicit DESTROY defined");
}

# --- AUTOLOAD sets $AUTOLOAD correctly for DESTROY ---
{
    my $autoload_name;
    {
        package DI_AutoloadName;
        sub new { bless {}, shift }
        sub AUTOLOAD {
            our $AUTOLOAD;
            $autoload_name = $AUTOLOAD;
        }
    }
    { my $obj = DI_AutoloadName->new; }
    is($autoload_name, "DI_AutoloadName::DESTROY",
        "\$AUTOLOAD set to full DESTROY name");
}

# --- DESTROY with C3 MRO ---
{
    my @log;
    {
        package DI_C3Base;
        use mro 'c3';
        sub new     { bless {}, shift }
        sub DESTROY { push @log, "c3_base" }
    }
    {
        package DI_C3Left;
        use mro 'c3';
        our @ISA = ('DI_C3Base');
    }
    {
        package DI_C3Right;
        use mro 'c3';
        our @ISA = ('DI_C3Base');
        sub DESTROY { push @log, "c3_right" }
    }
    {
        package DI_C3Diamond;
        use mro 'c3';
        our @ISA = ('DI_C3Left', 'DI_C3Right');
        sub new { bless {}, shift }
    }
    { my $obj = DI_C3Diamond->new; }
    # C3 MRO: DI_C3Diamond -> DI_C3Left -> DI_C3Right -> DI_C3Base
    # DI_C3Left has no DESTROY, DI_C3Right does
    is_deeply(\@log, ["c3_right"],
        "C3 MRO: DESTROY resolved correctly in diamond inheritance");
}

# --- DESTROY with empty DESTROY (no-op) ---
{
    my $parent_called = 0;
    {
        package DI_ParentNoOp;
        sub new     { bless {}, shift }
        sub DESTROY { $parent_called = 1 }
    }
    {
        package DI_ChildNoOp;
        our @ISA = ('DI_ParentNoOp');
        sub new     { bless {}, shift }
        sub DESTROY { }  # intentionally empty — does NOT call SUPER::DESTROY
    }
    { my $obj = DI_ChildNoOp->new; }
    is($parent_called, 0,
        "empty child DESTROY doesn't call parent (no implicit chaining)");
}

# --- Dynamic @ISA change before DESTROY ---
{
    my @log;
    {
        package DI_DynBase;
        sub DESTROY { push @log, "dyn_base" }
    }
    {
        package DI_DynAlt;
        sub DESTROY { push @log, "dyn_alt" }
    }
    {
        package DI_DynChild;
        our @ISA = ('DI_DynBase');
        sub new { bless {}, shift }
    }
    my $obj = DI_DynChild->new;
    @DI_DynChild::ISA = ('DI_DynAlt');  # change @ISA before DESTROY
    undef $obj;
    is_deeply(\@log, ["dyn_alt"],
        "DESTROY uses current \@ISA at destruction time, not bless time");
}

done_testing();
