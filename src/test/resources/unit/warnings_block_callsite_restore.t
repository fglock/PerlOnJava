use strict;
use Test::More tests => 3;

# caller()[9] at a warning-free call site must remain a usable warning mask
# when assigned inside a string eval.
my $bits = sub { (caller 0)[9] }->();
my $count;
local $SIG{__WARN__} = sub { $count++ };
eval '
    use warnings;
    BEGIN { ${^WARNING_BITS} = $bits }
    local $^W = 1;
    () = 1 + undef;
    $^W = 0;
    () = 1 + undef;
';
is($@, '', 'caller warning mask is accepted by eval compilation');
is($count, 1, 'caller warning mask preserves the expected warning state');

use warnings;
use attributes;
{
    no warnings 'misc';
}

my $warning = '';
local $SIG{__WARN__} = sub { $warning = shift };
attributes->import(__PACKAGE__, \&unknown_const_target, 'const');
like($warning, qr/^Useless use of attribute "const" at /,
    'leaving no warnings restores warning bits for the next call site');
