use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);

# =============================================================================
# splice_args_destroy.t — splice on @_ must not prematurely DESTROY caller's objects
#
# Tests: when splice removes blessed references from @_ (which contains aliases
# to the caller's variables), it must NOT decrement refCounts that @_ never
# incremented. This catches the bug where Operator.splice() called
# deferDecrementIfTracked() without checking runtimeArray.elementsOwned,
# causing the caller's $obj refCount to drop to 0 and trigger DESTROY while
# the object was still in scope.
#
# This is the exact pattern used by Class::Accessor::Grouped::get_inherited
# in DBIx::Class: splice @_, 0, 1, ref($_[0])
# =============================================================================

my @log;

{
    package SAD_Obj;
    sub new { bless {val => $_[1]}, $_[0] }
    sub DESTROY { push @log, "DESTROY:$_[0]->{val}" }
}

# --- Test 1: splice @_, 0, 1 (discard) must not trigger DESTROY ---
{
    @log = ();
    sub test_splice_discard {
        splice @_, 0, 1;
        return;
    }
    my $obj = SAD_Obj->new("A");
    test_splice_discard($obj);
    is_deeply(\@log, [], "splice \@_, 0, 1 does not trigger DESTROY");
    is($obj->{val}, "A", "object still valid after splice \@_ discard");
}

# --- Test 2: splice @_, 0, 1, ref($_[0]) (the DBIx::Class pattern) ---
{
    @log = ();
    sub test_splice_replace {
        splice @_, 0, 1, ref($_[0]);
        is($_[0], "SAD_Obj", "splice replacement is class name");
        return;
    }
    my $obj = SAD_Obj->new("B");
    my $weak = $obj;
    weaken($weak);
    test_splice_replace($obj);
    is_deeply(\@log, [], "splice \@_, 0, 1, ref(\$_[0]) does not trigger DESTROY");
    ok(defined($weak), "weak ref still alive after splice \@_ replace");
    is($obj->{val}, "B", "object still valid after splice \@_ replace");
}

# --- Test 3: splice on regular array DOES trigger DESTROY ---
{
    @log = ();
    my @arr;
    push @arr, SAD_Obj->new("C");
    splice @arr, 0, 1;
    is_deeply(\@log, ["DESTROY:C"], "splice on regular array triggers DESTROY");
}

# --- Test 4: splice on regular array with replacement triggers DESTROY ---
{
    @log = ();
    my @arr;
    push @arr, SAD_Obj->new("D");
    splice @arr, 0, 1, "replaced";
    is_deeply(\@log, ["DESTROY:D"], "splice on regular array with replacement triggers DESTROY");
    is($arr[0], "replaced", "replacement element is correct");
}

# --- Test 5: splice on regular array, captured return value stays alive ---
{
    @log = ();
    my @arr;
    push @arr, SAD_Obj->new("E");
    my @removed = splice @arr, 0, 1;
    is_deeply(\@log, [], "captured splice return keeps object alive");
    is($removed[0]->{val}, "E", "captured element is valid");
    @removed = ();
    is_deeply(\@log, ["DESTROY:E"], "clearing captured list triggers DESTROY");
}

# --- Test 6: shift @_ (for comparison) does not trigger DESTROY ---
{
    @log = ();
    sub test_shift {
        my $first = shift;
        return;
    }
    my $obj = SAD_Obj->new("F");
    test_shift($obj);
    is_deeply(\@log, [], "shift \@_ does not trigger DESTROY");
    is($obj->{val}, "F", "object still valid after shift \@_");
}

# --- Test 7: splice multiple elements from @_ ---
{
    @log = ();
    sub test_splice_multi {
        splice @_, 0, 2;
        return;
    }
    my $obj1 = SAD_Obj->new("G");
    my $obj2 = SAD_Obj->new("H");
    test_splice_multi($obj1, $obj2);
    is_deeply(\@log, [], "splice \@_, 0, 2 does not trigger DESTROY for either object");
    is($obj1->{val}, "G", "first object valid");
    is($obj2->{val}, "H", "second object valid");
}

# --- Test 8: weak ref survives splice @_ in nested call chain ---
{
    @log = ();
    sub inner_splice {
        splice @_, 0, 1, ref($_[0]);
        return;
    }
    sub outer_call {
        inner_splice(@_);
        return;
    }
    my $obj = SAD_Obj->new("I");
    my $weak = $obj;
    weaken($weak);
    outer_call($obj);
    ok(defined($weak), "weak ref survives splice in nested call");
    is($obj->{val}, "I", "object valid after nested splice");
    is_deeply(\@log, [], "no premature DESTROY in nested call chain");
}

done_testing();
