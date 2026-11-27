use strict;
use warnings;
use Test::More tests => 3;

# A reference to $#{[]} outlives the statement that built the anonymous
# array. On a reference-counted stack the $# magic variable keeps the array
# alive; otherwise the array is freed and the proxy is orphaned. This mirrors
# op/array.t test_arylen.

my $refcounted_stack = Internals::stack_refcounted() & 1;
my $last_index = \$#{[]};

is($$last_index, ($refcounted_stack ? -1 : undef),
    'the anonymous array behind a $# reference follows the stack model');

my @warnings;
{
    local $^W = 1;
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    $$last_index = 1000;
}

is(scalar @warnings, ($refcounted_stack ? 0 : 1),
    'writing the array length warns only when the array was freed');
if ($refcounted_stack) {
    is($$last_index, 1000, 'the retained array accepts the new length');
}
else {
    like($warnings[0], qr/^Attempt to set length of freed array/,
        'the freed anonymous array reports the expected warning');
}
