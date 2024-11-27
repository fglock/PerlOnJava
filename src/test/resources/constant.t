use strict;
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

print "not " if SEC != 0; say "ok # SEC constant";
print "not " if MIN != 1; say "ok # MIN constant";
print "not " if HOUR != 2; say "ok # HOUR constant";

# Test array constant
use constant WEEKDAYS => qw(Sunday Monday Tuesday Wednesday Thursday Friday Saturday);
print "not " if (WEEKDAYS)[0] ne 'Sunday'; say "ok # WEEKDAYS constant";

# Test usage in expressions
use constant DEBUG => 0;
print "not " if DEBUG != 0; say "ok # DEBUG constant";

# Test block-style constant definition
use constant {
    TRUE  => 1,
    FALSE => 0,
};

print "not " if TRUE != 1; say "ok # TRUE constant";
print "not " if FALSE != 0; say "ok # FALSE constant";

# Test case-insensitivity
use constant {
    Foo => 'bar',
    foo => 'baz',
};

print "not " if Foo ne 'bar'; say "ok # Foo constant";
print "not " if foo ne 'baz'; say "ok # foo constant";


