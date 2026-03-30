use strict;
use Test::More;
use feature 'say';
use constant;

# Define a small epsilon for floating-point comparison
my $epsilon = 1e-9;

# Test single constant
use constant PI => 4 * atan2(1, 1);
ok(abs(PI - 3.141592653589793) <= $epsilon, "PI constant <" . PI . ">");

# Test multiple constants
use constant {
    SEC   => 0,
    MIN   => 1,
    HOUR  => 2,
};

ok(!(SEC != 0), 'SEC constant');
ok(!(MIN != 1), 'MIN constant');
ok(!(HOUR != 2), 'HOUR constant');

# Test array constant
use constant WEEKDAYS => qw(Sunday Monday Tuesday Wednesday Thursday Friday Saturday);
ok(!((WEEKDAYS)[0] ne 'Sunday'), 'WEEKDAYS constant');

# Test usage in expressions
use constant DEBUG => 0;
ok(!(DEBUG != 0), 'DEBUG constant');

# Test block-style constant definition
use constant {
    TRUE  => 1,
    FALSE => 0,
};

ok(!(TRUE != 1), 'TRUE constant');
ok(!(FALSE != 0), 'FALSE constant');

# Test case-insensitivity
use constant {
    Foo => 'bar',
    foo => 'baz',
};

ok(!(Foo ne 'bar'), 'Foo constant');
ok(!(foo ne 'baz'), 'foo constant');

# --- Constant folding in expressions ---

# Arithmetic folding with user-defined constants
use constant X => 10;
use constant Y => 20;
is(X + Y, 30, 'constant addition folded');
is(X * 2, 20, 'constant * literal folded');
is(X + Y + 5, 35, 'cascading constant fold');
is(X ** 2, 100, 'constant exponentiation');
is(Y - X, 10, 'constant subtraction');
is(Y / X, 2, 'constant division');

# String constant folding
use constant GREETING => 'hello';
is(GREETING . ' world', 'hello world', 'constant string concatenation');

# Dead code elimination (already works, verify preserved)
if (DEBUG) { die "should not reach here" }
pass('dead code eliminated with constant false');

use constant ENABLED => 1;
my $reached = 0;
if (ENABLED) { $reached = 1 }
is($reached, 1, 'dead code elimination with constant true');

# List constants NOT folded (should still work correctly)
use constant DAYS => qw(Mon Tue Wed);
is((DAYS)[0], 'Mon', 'list constant not broken by folding');
is((DAYS)[2], 'Wed', 'list constant element access');

# Reference constants NOT folded (same object identity preserved)
use constant AREF => [1, 2, 3];
push @{AREF()}, 4;
is(scalar @{AREF()}, 4, 'reference constant is same object');

# Nested constant expressions
use constant A => 3;
use constant B => 7;
is(A * B + 1, 22, 'nested constant expression folding');
is((A + B) * 2, 20, 'parenthesized constant expression');

# Constant in ternary
use constant PICK_FIRST => 1;
is((PICK_FIRST ? 'yes' : 'no'), 'yes', 'constant in ternary condition - true');
use constant PICK_SECOND => 0;
is((PICK_SECOND ? 'yes' : 'no'), 'no', 'constant in ternary condition - false');

# Constant with logical operators
use constant TRUTHY => 42;
use constant FALSY => 0;
is((TRUTHY && 'ok'), 'ok', 'constant && with true LHS');
is((FALSY && 'ok'), 0, 'constant && with false LHS');
is((TRUTHY || 'fallback'), 42, 'constant || with true LHS');
is((FALSY || 'fallback'), 'fallback', 'constant || with false LHS');

# Constant in eval
{
    use constant EVAL_CONST => 99;
    my $result = eval 'EVAL_CONST + 1';
    is($result, 100, 'constant folding works in eval');
}

# Undef constant
use constant UNDEF_CONST => undef;
ok(!defined(UNDEF_CONST), 'undef constant is undefined');
is((UNDEF_CONST // 'default'), 'default', 'undef constant folds with //');

done_testing();
