use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken isweak);

# =============================================================================
# weaken_destroy.t — Interaction between weaken() and DESTROY
#
# Tests: DESTROY fires when last strong ref goes, weak ref becomes undef,
# circular references broken by weaken, DESTROY ordering with weak refs.
# =============================================================================

# --- DESTROY fires when last strong ref gone, weak ref becomes undef ---
{
    my @log;
    {
        package WD_Basic;
        sub new     { bless { id => $_[1] }, $_[0] }
        sub DESTROY { push @log, "destroyed:" . $_[0]->{id} }
    }
    my $strong = WD_Basic->new("wd1");
    my $weak = $strong;
    weaken($weak);
    ok(defined($weak), "weak ref defined while strong exists");
    undef $strong;
    is_deeply(\@log, ["destroyed:wd1"], "DESTROY fires when last strong ref gone");
    ok(!defined($weak), "weak ref is undef after DESTROY");
}

# --- Two strong refs + one weak: DESTROY waits for both strong refs ---
{
    my @log;
    {
        package WD_TwoStrong;
        sub new     { bless {}, shift }
        sub DESTROY { push @log, "destroyed" }
    }
    my $a = WD_TwoStrong->new;
    my $b = $a;
    my $weak = $a;
    weaken($weak);
    undef $a;
    is_deeply(\@log, [], "DESTROY not called (one strong ref remains)");
    ok(defined($weak), "weak ref still defined");
    undef $b;
    is_deeply(\@log, ["destroyed"], "DESTROY called when last strong ref gone");
    ok(!defined($weak), "weak ref undef after DESTROY");
}

# --- Circular reference broken by weaken ---
{
    my @log;
    {
        package WD_CircA;
        sub new     { bless { peer => undef }, shift }
        sub DESTROY { push @log, "A_destroyed" }
    }
    {
        package WD_CircB;
        sub new     { bless { peer => undef }, shift }
        sub DESTROY { push @log, "B_destroyed" }
    }
    {
        my $a = WD_CircA->new;
        my $b = WD_CircB->new;
        $a->{peer} = $b;
        $b->{peer} = $a;
        weaken($b->{peer});  # break the cycle
    }
    # $a's last strong ref is the lexical; when it leaves scope, $a is destroyed.
    # $a's DESTROY happens, then $b has no strong refs left, so $b is destroyed.
    my %seen = map { $_ => 1 } @log;
    ok($seen{A_destroyed}, "A destroyed (circular ref broken by weaken)");
    ok($seen{B_destroyed}, "B destroyed (circular ref broken by weaken)");
}

# --- Self-referencing object with weaken ---
{
    my @log;
    {
        package WD_SelfRef;
        sub new {
            my $self = bless { me => undef }, shift;
            $self->{me} = $self;
            Scalar::Util::weaken($self->{me});
            return $self;
        }
        sub DESTROY { push @log, "self_destroyed" }
    }
    { my $obj = WD_SelfRef->new; }
    is_deeply(\@log, ["self_destroyed"],
        "self-referencing object destroyed when weaken breaks cycle");
}

# --- Tree with parent back-pointer weakened ---
{
    my @log;
    {
        package WD_TreeNode;
        sub new {
            my ($class, $name, $parent) = @_;
            my $self = bless { name => $name, parent => undef, children => [] }, $class;
            if ($parent) {
                $self->{parent} = $parent;
                Scalar::Util::weaken($self->{parent});
                push @{$parent->{children}}, $self;
            }
            return $self;
        }
        sub DESTROY { push @log, "tree:" . $_[0]->{name} }
    }
    {
        my $root = WD_TreeNode->new("root");
        my $child1 = WD_TreeNode->new("child1", $root);
        my $child2 = WD_TreeNode->new("child2", $root);
    }
    is(scalar @log, 3, "all tree nodes destroyed");
    my %seen = map { $_ => 1 } @log;
    ok($seen{"tree:root"}, "root destroyed");
    ok($seen{"tree:child1"}, "child1 destroyed");
    ok($seen{"tree:child2"}, "child2 destroyed");
}

# --- DESTROY and weak ref visibility depends on destruction order ---
# When a scope exits, the destruction order of lexicals is implementation-
# defined. A weak ref to another lexical in the same scope may or may not
# be valid during DESTROY, depending on which object is freed first.
{
    my $weak_seen;
    {
        package WD_AccessWeak;
        sub new     { bless { partner => undef }, shift }
        sub DESTROY {
            my $self = shift;
            $weak_seen = defined($self->{partner}) ? "defined" : "undef";
        }
    }
    {
        my $b = { data => "partner_data" };
        my $a = WD_AccessWeak->new;
        $a->{partner} = $b;
        weaken($a->{partner});
    }
    # We can't guarantee the order, so just verify DESTROY ran without crashing
    ok(defined($weak_seen), "DESTROY ran and checked weak ref without crash");
}

# --- weaken on the only ref: DESTROY fires immediately ---
{
    my @log;
    {
        package WD_WeakenOnly;
        sub new     { bless {}, shift }
        sub DESTROY { push @log, "only_destroyed" }
    }
    my $ref = WD_WeakenOnly->new;
    weaken($ref);
    # $ref is now the only ref, and it's weak — no strong refs remain
    is_deeply(\@log, ["only_destroyed"],
        "DESTROY fires immediately when the only ref is weakened");
    ok(!defined($ref), "weak ref is undef after immediate DESTROY");
}

# --- Weak ref in hash value, strong ref elsewhere ---
{
    my @log;
    {
        package WD_HashWeak;
        sub new     { bless { id => $_[1] }, $_[0] }
        sub DESTROY { push @log, "d:" . $_[0]->{id} }
    }
    my $strong = WD_HashWeak->new("hw1");
    my %cache;
    $cache{obj} = $strong;
    weaken($cache{obj});
    ok(isweak($cache{obj}), "hash value is weak");
    is($cache{obj}->{id}, "hw1", "can access through weak hash value");
    undef $strong;
    is_deeply(\@log, ["d:hw1"], "DESTROY fires");
    ok(!defined($cache{obj}), "weak hash value becomes undef");
}

# --- Weak ref in array element ---
{
    my @log;
    {
        package WD_ArrayWeak;
        sub new     { bless { id => $_[1] }, $_[0] }
        sub DESTROY { push @log, "d:" . $_[0]->{id} }
    }
    my $strong = WD_ArrayWeak->new("aw1");
    my @arr;
    $arr[0] = $strong;
    weaken($arr[0]);
    ok(isweak($arr[0]), "array element is weak");
    undef $strong;
    is_deeply(\@log, ["d:aw1"], "DESTROY fires");
    ok(!defined($arr[0]), "weak array element becomes undef");
}

done_testing();
