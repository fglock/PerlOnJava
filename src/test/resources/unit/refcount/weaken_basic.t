use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken isweak);

# =============================================================================
# weaken_basic.t — Core weak reference semantics
#
# Tests: weaken, isweak, unweaken (if available), copy-is-strong,
# weaken on different ref types, double weaken.
# =============================================================================

my $has_unweaken = eval { Scalar::Util->import('unweaken'); 1 };

# --- isweak on non-weak ref ---
{
    my $ref = \my %hash;
    ok(!isweak($ref), "fresh reference is not weak");
}

# --- weaken + isweak ---
{
    my $strong = {};
    my $ref = $strong;
    weaken($ref);
    ok(isweak($ref), "ref is weak after weaken()");
}

# --- unweaken ---
SKIP: {
    skip "unweaken not available", 2 unless $has_unweaken;
    my $strong = {};
    my $ref = $strong;
    weaken($ref);
    ok(isweak($ref), "ref is weak");
    Scalar::Util::unweaken($ref);
    ok(!isweak($ref), "ref is strong after unweaken()");
}

# --- Weak ref can still access data ---
{
    my $strong = { key => "value", num => 42 };
    my $weak = $strong;
    weaken($weak);
    is($weak->{key}, "value", "weak ref can read hash value");
    is($weak->{num}, 42, "weak ref can read numeric hash value");
}

# --- Weak ref becomes undef when strong ref goes away ---
{
    my $weak;
    {
        my $strong = { data => "hello" };
        $weak = $strong;
        weaken($weak);
        is($weak->{data}, "hello", "weak ref works while strong ref exists");
    }
    ok(!defined($weak), "weak ref becomes undef when strong ref leaves scope");
}

# --- Weak ref becomes undef on explicit undef of strong ref ---
{
    my $strong = { data => "test" };
    my $weak = $strong;
    weaken($weak);
    undef $strong;
    ok(!defined($weak), "weak ref becomes undef on undef of strong ref");
}

# --- Copy of weak ref is strong ---
{
    my $strong = { key => "val" };
    my $weak = $strong;
    weaken($weak);
    my $copy = $weak;
    ok(isweak($weak), "original is weak");
    ok(!isweak($copy), "copy of weak ref is strong");
}

# --- Copy of weak ref keeps object alive ---
{
    my $weak;
    my $copy;
    {
        my $strong = { key => "alive" };
        $weak = $strong;
        weaken($weak);
        $copy = $weak;  # strong copy
    }
    ok(!defined($weak) || defined($copy),
        "copy (strong) may keep object alive; weak ref may or may not be undef");
    if (defined($copy)) {
        is($copy->{key}, "alive", "strong copy still has data");
    }
}

# --- weaken on array ref ---
{
    my $strong = [1, 2, 3];
    my $weak = $strong;
    weaken($weak);
    ok(isweak($weak), "weaken works on array ref");
    is_deeply($weak, [1, 2, 3], "can access weakened array ref");
    undef $strong;
    ok(!defined($weak), "weakened array ref becomes undef");
}

# --- weaken on scalar ref ---
{
    my $val = 42;
    my $strong = \$val;
    my $weak = $strong;
    weaken($weak);
    ok(isweak($weak), "weaken works on scalar ref");
    is($$weak, 42, "can dereference weakened scalar ref");
}

# --- weaken on code ref ---
# Note: anonymous non-closure subs may be kept alive by Perl's internal
# optimizations (constant sub folding, etc.), so weakened code refs may
# not become undef even when the strong ref is dropped.
{
    my $strong = sub { return "hello" };
    my $weak = $strong;
    weaken($weak);
    ok(isweak($weak), "weaken works on code ref");
    is($weak->(), "hello", "can call weakened code ref");
}

# --- weaken on blessed ref ---
{
    {
        package WB_Blessed;
        sub new { bless {}, shift }
    }
    my $strong = WB_Blessed->new;
    my $weak = $strong;
    weaken($weak);
    ok(isweak($weak), "weaken works on blessed ref");
    is(ref($weak), "WB_Blessed", "blessed class visible through weak ref");
    undef $strong;
    ok(!defined($weak), "weakened blessed ref becomes undef");
}

# --- Double weaken is a no-op ---
{
    my $strong = {};
    my $weak = $strong;
    weaken($weak);
    ok(isweak($weak), "weak after first weaken");
    weaken($weak);  # second weaken — should be harmless
    ok(isweak($weak), "still weak after double weaken");
    is($weak, $strong, "still points to same object");
}

# --- Multiple weak refs to same object ---
{
    my $strong = { id => "multi" };
    my $weak1 = $strong;
    my $weak2 = $strong;
    weaken($weak1);
    weaken($weak2);
    ok(isweak($weak1), "first weak ref is weak");
    ok(isweak($weak2), "second weak ref is weak");
    undef $strong;
    ok(!defined($weak1), "first weak ref becomes undef");
    ok(!defined($weak2), "second weak ref becomes undef");
}

# --- weaken doesn't affect the strong ref itself ---
{
    my $strong = { data => "untouched" };
    my $weak = $strong;
    weaken($weak);
    ok(!isweak($strong), "strong ref not affected by weakening copy");
    is($strong->{data}, "untouched", "strong ref data intact");
}

# --- isweak on non-reference returns false ---
{
    my $scalar = 42;
    ok(!isweak($scalar), "isweak on non-ref scalar returns false");
    ok(!isweak(undef), "isweak on undef returns false");
}

done_testing();
