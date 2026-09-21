use strict;
use warnings;
use Test::More tests => 1;

my $scalar;
my $prototype_error = eval q{
    sub grouped (\[%@]) { 1 }
    grouped $scalar;
};
like($@, qr/Type of arg 1 to main::grouped must be one of \[%@\]/,
    'grouped backslash prototype rejects the wrong sigil');
