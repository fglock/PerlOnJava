use strict;
use warnings;
use Test::More tests => 8;

like 'aa', qr/\A(a)(?-1)\z/,
    'negative relative group call targets the preceding capture';
unlike 'ab', qr/\A(a)(?-1)\z/,
    'negative relative group call executes the target pattern';
like 'aa', qr/\A(?+1)(a)\z/,
    'positive relative group call targets a following capture';
unlike 'ba', qr/\A(?+1)(a)\z/,
    'positive relative group call executes the following target pattern';

eval q{qr/((?+2147483647))/};
like $@, qr/^Invalid reference to group in regex;/,
    'large positive relative call reports an invalid group reference';

eval q{qr/((?-2147483647))/};
like $@, qr/^Reference to nonexistent group in regex;/,
    'large negative relative call reports a nonexistent group reference';

eval q{qr/((?+18446744073709551615))/};
like $@, qr/^Invalid reference to group in regex;/,
    'overflowing positive relative call reports an invalid group reference';

eval q{qr/((?-18446744073709551615))/};
like $@, qr/^Invalid reference to group in regex;/,
    'overflowing negative relative call reports an invalid group reference';
