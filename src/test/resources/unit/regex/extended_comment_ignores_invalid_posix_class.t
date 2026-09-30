use strict;
use warnings;
use Test::More;

my $commented = eval {
    qr/(?[ [a] # [[:notaclass:]] and \8 are comment text
    ])/;
};
is($@, '', 'invalid POSIX-looking text in an extended-set comment is ignored');
like('a', $commented, 'the set before the comment still matches');
unlike('b', $commented, 'the set before the comment remains restrictive');

my $valid = eval { qr/(?[ [:alpha:] ]) /x };
is($@, '', 'a real POSIX class in an extended set still compiles');
like('z', $valid, 'the real POSIX class remains active');

my $invalid = eval q{ qr/(?[ [[:notaclass:]] ]) /x };
like($@, qr/^POSIX class \[:notaclass:\] unknown/,
    'an invalid POSIX class outside a comment still reports its error');

done_testing;
