use strict;
use warnings;
use feature 'switch';
no warnings 'experimental::smartmatch';
use Test::More;

my @errors;
for (1, 'two') {
    when ('two') {
        eval { break };
        push @errors, $@;
    }
}

is scalar @errors, 1, 'eval continues after break in a loop topicalizer';
like $errors[0], qr/^Can't "break" in a loop topicalizer/,
    'eval captures the loop-topicalizer break diagnostic';

done_testing;
