use feature 'say';
use strict;
use Test::More;
use warnings;

{
    package StringOnly;
    use overload
        '""' => \&as_string,
        fallback => 1;

    sub new {
        my ($class, $value) = @_;
        return bless { value => $value }, $class;
    }

    sub as_string {
        my $self = shift;
        return $self->{value};
    }
}

{
    package NumericOnly;
    use overload
        '0+' => \&as_number,
        fallback => 1;

    sub new {
        my ($class, $value) = @_;
        return bless { value => $value }, $class;
    }

    sub as_number {
        my $self = shift;
        return $self->{value};
    }
}

# Create test objects
my $str_obj = StringOnly->new(42);
my $num_obj = NumericOnly->new(42);

# Test StringOnly class - defined string operation
ok(!("$str_obj" ne "42"), 'string overload works');

# Test StringOnly class - fallback numeric
ok(!((0 + $str_obj) != 42), 'numeric fallback works on StringOnly');

# Test NumericOnly class - defined numeric operation
ok(!((0 + $num_obj) != 42), 'numeric overload works');

# Test NumericOnly class - fallback string
my $num_str = "$num_obj";
ok(!($num_str ne "42"), 'string fallback works on NumericOnly (got: \'$num_str\')');

# Test in operations
my $sum = 10 + $str_obj;
ok(!($sum != 52), 'numeric fallback works in addition');

# Test string concatenation
my $concat = "Value: " . $num_obj;
ok(!($concat ne "Value: 42"), 'string fallback works in concatenation (got: \'$concat\')');

# -----------------------------------------------------------------------
# Tests for "use overload; with no operators" fallback behaviour
#
# Standard Perl: a class that calls "use overload;" (no arguments) is
# technically overloaded (overload::Overloaded returns true) but has no
# operator methods.  String/numeric operators on such objects should
# silently fall back to native comparison rather than dying with
# "Operation '...': no method found".
# -----------------------------------------------------------------------

{
    package NoOperators;
    # "use overload;" with no arguments: package is marked overloaded
    # but no operator methods are defined.
    use overload;

    sub new { bless {}, shift }
}

{
    package OneOperator;
    # Has one operator (+), but not eq/ne/cmp/etc.
    # fallback is not set → default is undef → should throw "no method found"
    # for unhandled operators like eq.
    use overload '+' => sub { 42 };

    sub new { bless {}, shift }
}

{
    package OneOpFallback1;
    # Has one operator, but fallback => 1 → silently fall back to native.
    use overload '+' => sub { 42 }, fallback => 1;

    sub new { bless {}, shift }
}

{
    package NoOpFallback0;
    # No operators, but explicit fallback => 0.
    # Perl: the fallback => 0 is still stored, so the package HAS a ()
    # glob, but no actual operator methods.  When eq is requested,
    # tryTwoArgumentNomethod throws immediately because fallback=0.
    use overload fallback => 0;

    sub new { bless {}, shift }
}

my $no_ops   = NoOperators->new;
my $one_op   = OneOperator->new;
my $one_fb1  = OneOpFallback1->new;
my $no_op_f0 = NoOpFallback0->new;

# Case 1: "use overload;" with no operators → native fallback, no error
{
    my $ok = eval { ($no_ops eq "anything") ? 1 : 0 };
    ok(!$@, 'use overload; (no ops) does not throw for eq');
    ok(defined $ok, 'use overload; (no ops) eq returns a value');
}

# Case 2: one operator defined, fallback not set → "no method found" for eq
{
    eval { my $x = ($one_op eq "anything") };
    like($@, qr/no method found/, 'one operator, fallback undef: eq throws "no method found"');
}

# Case 3: one operator + fallback => 1 → native fallback, no error
{
    my $ok = eval { ($one_fb1 eq "anything") ? 1 : 0 };
    ok(!$@, 'one operator + fallback=>1 does not throw for eq');
    ok(defined $ok, 'one operator + fallback=>1 eq returns a value');
}

# Case 4: no operators + explicit fallback => 0 → throws for eq
{
    eval { my $x = ($no_op_f0 eq "anything") };
    like($@, qr/no method found/, 'no ops + fallback=>0 throws "no method found" for eq');
}

done_testing();

