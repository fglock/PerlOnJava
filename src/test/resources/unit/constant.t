use strict;
use Test::More;
use feature 'say';
use constant;

# Define a small epsilon for floating-point comparison
my $epsilon = 1e-9;

# Test single constant
use constant PI => 4 * atan2(1, 1);
print "not " if abs(PI - 3.141592653589793) > $epsilon; say "ok # PI constant <" . PI . ">";

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

done_testing();
