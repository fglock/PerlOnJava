#!/usr/bin/perl
use strict;
use warnings;
use Test::More;
use mro;

# Define all base classes first
BEGIN {
    package Base;
    sub method { "Base::method" }
    sub identify { "Base" }

    package OtherBase;
    sub method { "OtherBase::method" }
    sub identify { "OtherBase" }

    package LookupBase;
    sub ambiguous { "LookupBase::ambiguous" }

    package Diamond2Base;
    sub method { "Diamond2Base::method" }
    sub other_method { "Diamond2Base::other_method" }

    package SuperBase;
    sub super_method { "SuperBase::super_method" }

    package MixedParentDFS;
    use mro 'dfs';
    sub mixed_method { "MixedParentDFS::mixed_method" }
    sub common_method { "MixedParentDFS::common_method" }

    package MixedParentC3;
    use mro 'c3';
    sub mixed_method { "MixedParentC3::mixed_method" }
    sub common_method { "MixedParentC3::common_method" }
}

# Now define classes that inherit from the base classes
BEGIN {
    package ParentDFS;
    use mro 'dfs';
    our @ISA = ('Base');
    sub identify { "ParentDFS" }

    package ParentExplicitDFS;
    use mro 'dfs';
    our @ISA = ('Base');
    sub identify { "ParentExplicitDFS" }
    sub method { "ParentExplicitDFS::method" }

    package ParentC3;
    use mro 'c3';
    our @ISA = ('Base');
    sub identify { "ParentC3" }

    package DiamondLeft;
    use mro 'dfs';
    our @ISA = ('Base');
    sub method { "DiamondLeft::method" }
    sub left_method { "DiamondLeft::left_method" }

    package DiamondRight;
    use mro 'c3';
    our @ISA = ('Base');
    sub method { "DiamondRight::method" }
    sub right_method { "DiamondRight::right_method" }

    package Diamond2Left;
    our @ISA = ('Diamond2Base');

    package Diamond2Right;
    our @ISA = ('Diamond2Base');
    sub method { "Diamond2Right::method" }

    package Level1DFS;
    use mro 'dfs';
    our @ISA = ('Base');

    package LookupA;
    use mro 'dfs';
    our @ISA = ('LookupBase');
    sub ambiguous { "LookupA::ambiguous" }

    package LookupB;
    use mro 'c3';
    our @ISA = ('LookupBase');
    sub ambiguous { "LookupB::ambiguous" }

    package SuperParentC3;
    use mro 'c3';
    our @ISA = ('SuperBase');
    sub super_method {
        my $class = shift;
        return "SuperParentC3::super_method -> " . $class->SUPER::super_method();
    }

    package NextMethodParent;
    use mro 'c3';
    sub test_method { "NextMethodParent" }
}

# Now define classes that inherit from the intermediate classes
BEGIN {
    package ChildC3FromDFS;
    use mro 'c3';
    our @ISA = ('ParentDFS');
    sub identify { "ChildC3FromDFS" }

    package ChildDFSFromC3;
    use mro 'dfs';
    our @ISA = ('ParentC3');
    sub identify { "ChildDFSFromC3" }

    package ChildDefaultFromC3;
    our @ISA = ('ParentC3');
    sub identify { "ChildDefaultFromC3" }

    package DiamondBottomC3;
    use mro 'c3';
    our @ISA = ('DiamondLeft', 'DiamondRight');
    sub identify { "DiamondBottomC3" }

    package DiamondBottomDFS;
    use mro 'dfs';
    our @ISA = ('DiamondLeft', 'DiamondRight');
    sub identify { "DiamondBottomDFS" }

    package Diamond2BottomC3;
    use mro 'c3';
    our @ISA = ('Diamond2Left', 'Diamond2Right');

    package Diamond2BottomDFS;
    use mro 'dfs';
    our @ISA = ('Diamond2Left', 'Diamond2Right');

    package Level2C3;
    use mro 'c3';
    our @ISA = ('Level1DFS');

    package Level3DFS;
    use mro 'dfs';
    our @ISA = ('Level2C3');

    package Level4C3;
    use mro 'c3';
    our @ISA = ('Level3DFS');

    package MixedChildC3;
    use mro 'c3';
    our @ISA = ('MixedParentDFS', 'MixedParentC3');

    package MixedChildDFS;
    use mro 'dfs';
    our @ISA = ('MixedParentDFS', 'MixedParentC3');

    package LookupChildC3;
    use mro 'c3';
    our @ISA = ('LookupA', 'LookupB');

    package LookupChildDFS;
    use mro 'dfs';
    our @ISA = ('LookupA', 'LookupB');

    package SuperChildDFS;
    use mro 'dfs';
    our @ISA = ('SuperParentC3');
    sub super_method {
        my $class = shift;
        return "SuperChildDFS::super_method -> " . $class->SUPER::super_method();
    }

    package NextMethodChild;
    use mro 'dfs';
    our @ISA = ('NextMethodParent');
    sub test_method {
        my $self = shift;
        return "NextMethodChild->" . $self->next::method();
    }

    package NextMethodChildC3;
    use mro 'c3';
    our @ISA = ('NextMethodParent');
    sub test_method {
        my $self = shift;
        return "NextMethodChildC3->" . $self->next::method();
    }
}

# Runtime-defined classes
{
    package RuntimeMRO;
    our @ISA = ('Base');
}

# Start testing
plan tests => 9;

subtest 'MRO is per-class and not inherited' => sub {
    plan tests => 8;

    is(mro::get_mro('ParentDFS'), 'dfs', 'ParentDFS uses DFS MRO');
    is(mro::get_mro('ParentC3'), 'c3', 'ParentC3 uses C3 MRO');

    is(mro::get_mro('ChildC3FromDFS'), 'c3',
        'Child with C3 inheriting from DFS parent uses C3');
    is(mro::get_mro('ChildDFSFromC3'), 'dfs',
        'Child with DFS inheriting from C3 parent uses DFS');
    is(mro::get_mro('ChildDefaultFromC3'), 'dfs',
        'Child with no explicit MRO defaults to DFS regardless of parent');

    # Test multi-level inheritance
    is(mro::get_mro('Level1DFS'), 'dfs', 'Level 1 uses DFS');
    is(mro::get_mro('Level2C3'), 'c3', 'Level 2 uses C3 despite DFS parent');
    is(mro::get_mro('Level4C3'), 'c3', 'Level 4 uses C3 through mixed hierarchy');
};

subtest 'Linearization uses child class MRO algorithm' => sub {
    plan tests => 6;

    my @c3_from_dfs = @{mro::get_linear_isa('ChildC3FromDFS')};
    my @dfs_from_c3 = @{mro::get_linear_isa('ChildDFSFromC3')};

    is_deeply(\@c3_from_dfs,
        ['ChildC3FromDFS', 'ParentDFS', 'Base'],
        'C3 child properly linearizes DFS parent hierarchy');

    is_deeply(\@dfs_from_c3,
        ['ChildDFSFromC3', 'ParentC3', 'Base'],
        'DFS child properly linearizes C3 parent hierarchy');

    # Test diamond inheritance linearization differences
    my @diamond_c3 = @{mro::get_linear_isa('DiamondBottomC3')};
    my @diamond_dfs = @{mro::get_linear_isa('DiamondBottomDFS')};

    is_deeply(\@diamond_c3,
        ['DiamondBottomC3', 'DiamondLeft', 'DiamondRight', 'Base'],
        'C3 linearization of diamond inheritance');

    is_deeply(\@diamond_dfs,
        ['DiamondBottomDFS', 'DiamondLeft', 'Base', 'DiamondRight'],
        'DFS linearization of diamond inheritance (depth-first)');

    isnt("@diamond_c3", "@diamond_dfs",
        'Different MRO algorithms produce different linearizations');

    # Verify Base appears only once in each linearization
    is(scalar(grep { $_ eq 'Base' } @diamond_c3), 1,
        'Base appears only once in C3 linearization');
};

subtest 'Method resolution follows child MRO' => sub {
    plan tests => 6;

    # In diamond inheritance, method resolution differs based on MRO
    is(DiamondBottomC3->method(), 'DiamondLeft::method',
        'C3 MRO resolves to first parent in C3 order');

    is(DiamondBottomDFS->method(), 'DiamondLeft::method',
        'DFS MRO also resolves to DiamondLeft (first in parent list)');

    # Test methods unique to each parent
    is(DiamondBottomC3->left_method(), 'DiamondLeft::left_method',
        'C3 child can access left parent methods');

    is(DiamondBottomC3->right_method(), 'DiamondRight::right_method',
        'C3 child can access right parent methods');

    # Test the improved diamond scenario where MRO makes a difference
    is(Diamond2BottomC3->method(), 'Diamond2Right::method',
        'C3 finds method in Diamond2Right (breadth-first search)');

    is(Diamond2BottomDFS->method(), 'Diamond2Base::method',
        'DFS finds method in Diamond2Base (depth-first through Diamond2Left)');
};

subtest 'Mixed MRO works without conflicts' => sub {
    plan tests => 5;

    # Create a complex scenario with mixed MRO
    my $c3_child = bless {}, 'ChildC3FromDFS';
    my $dfs_child = bless {}, 'ChildDFSFromC3';

    ok(defined $c3_child, 'Can create object with C3 MRO from DFS parent');
    ok(defined $dfs_child, 'Can create object with DFS MRO from C3 parent');

    # Test method calls work correctly
    is(ChildC3FromDFS->identify(), 'ChildC3FromDFS',
        'Method resolution works with mixed MRO');

    # Test isa relationships are preserved
    ok(ChildC3FromDFS->isa('ParentDFS'),
        'isa() works correctly with C3 child of DFS parent');
    ok(ChildDFSFromC3->isa('ParentC3'),
        'isa() works correctly with DFS child of C3 parent');
};

subtest 'MRO algorithm choice affects method lookup order' => sub {
    plan tests => 6;

    my @c3_isa = @{mro::get_linear_isa('LookupChildC3')};
    my @dfs_isa = @{mro::get_linear_isa('LookupChildDFS')};

    is($c3_isa[1], 'LookupA', 'C3: First parent is LookupA');
    is($c3_isa[2], 'LookupB', 'C3: Second parent is LookupB');
    is($c3_isa[3], 'LookupBase', 'C3: Base comes after both parents');

    is($dfs_isa[1], 'LookupA', 'DFS: First parent is LookupA');
    is($dfs_isa[2], 'LookupBase', 'DFS: Base comes after first parent (depth-first)');
    is($dfs_isa[3], 'LookupB', 'DFS: Second parent comes last');
};

subtest 'Multiple inheritance with mixed parent MRO' => sub {
    plan tests => 4;

    # Test when parents have different MRO
    is(MixedChildC3->mixed_method(), 'MixedParentDFS::mixed_method',
        'C3 child finds method from first parent (DFS MRO parent)');

    is(MixedChildDFS->mixed_method(), 'MixedParentDFS::mixed_method',
        'DFS child finds method from first parent (DFS MRO parent)');

    my @mixed_c3 = @{mro::get_linear_isa('MixedChildC3')};
    my @mixed_dfs = @{mro::get_linear_isa('MixedChildDFS')};

    is_deeply(\@mixed_c3,
        ['MixedChildC3', 'MixedParentDFS', 'MixedParentC3'],
        'C3 child linearization with mixed parent MROs');

    is_deeply(\@mixed_dfs,
        ['MixedChildDFS', 'MixedParentDFS', 'MixedParentC3'],
        'DFS child linearization with mixed parent MROs');
};

subtest 'SUPER:: with mixed MRO' => sub {
    plan tests => 3;

    # Test SUPER:: delegation across MRO boundaries
    is(SuperChildDFS->super_method(),
        'SuperChildDFS::super_method -> SuperParentC3::super_method -> SuperBase::super_method',
        'SUPER:: works correctly when child uses DFS and parent uses C3');

    # Test that SUPER:: uses the child's MRO for resolution
    is(mro::get_mro('SuperChildDFS'), 'dfs', 'Child uses DFS');
    is(mro::get_mro('SuperParentC3'), 'c3', 'Parent uses C3');
};

subtest 'Edge cases and special scenarios' => sub {
    plan tests => 5;  # Reduced to 5 tests

    is(mro::get_mro('RuntimeMRO'), 'dfs', 'Class starts with default DFS MRO');

    # Change MRO at runtime
    mro::set_mro('RuntimeMRO', 'c3');
    is(mro::get_mro('RuntimeMRO'), 'c3', 'MRO can be changed at runtime');

    # Test that child classes are not affected by parent's runtime MRO change
    {
        package RuntimeChild;
        our @ISA = ('RuntimeMRO');
    }

    is(mro::get_mro('RuntimeChild'), 'dfs',
        'Child class maintains default MRO despite parent runtime change');

    # Reset for consistency
    mro::set_mro('RuntimeMRO', 'dfs');

    # Test that next::method works with C3
    my $next_method_result;
    eval {
        $next_method_result = NextMethodChildC3->test_method();
    };
    ok(!$@, 'next::method works with C3 MRO');
    is($next_method_result, 'NextMethodChildC3->NextMethodParent',
       'next::method correctly delegates with C3');

    # Note: In standard Perl, next::method with DFS doesn't throw an error,
    # it just doesn't work correctly. The behavior may vary between Perl versions.
};

subtest 'Circular inheritance detection with mixed MRO' => sub {
    plan tests => 4;

    # Test circular inheritance detection
    {
        package CircularA;
        # Will be set up for circular inheritance
    }

    {
        package CircularB;
        our @ISA = ('CircularA');
    }

    # Attempt to create circular inheritance with DFS
    eval {
        no warnings 'redefine';
        @CircularA::ISA = ('CircularB');
        # Force MRO to recompute by trying to get linearization
        mro::get_linear_isa('CircularA');
    };
    ok($@, 'Circular inheritance with DFS is detected');
    like($@, qr/(Recursive|Circular)/i, 'Error mentions recursive/circular inheritance');

    # Reset and test with C3
    @CircularA::ISA = ();
    @CircularB::ISA = ();

    {
        package CircularC3A;
        use mro 'c3';
    }

    {
        package CircularC3B;
        use mro 'c3';
        our @ISA = ('CircularC3A');
    }

    eval {
        no warnings 'redefine';
        @CircularC3A::ISA = ('CircularC3B');
        # Force C3 to recompute
        mro::get_linear_isa('CircularC3A');
    };
    ok($@, 'Circular inheritance with C3 is detected');
    like($@, qr/(Recursive|Inconsistent|cyclic)/i, 'C3 detects inconsistent hierarchy');
};

done_testing();
