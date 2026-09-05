use strict;
use warnings;
use Test::More;

my $last_index = do {
    my @lexical;
    \$#lexical;
};

ok !defined($$last_index), 'reference to lexical array last index is undef after scope exit';

my @warnings;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    $$last_index = 1000;
}
like $warnings[0], qr/^Attempt to set length of freed array/,
    'writing an orphaned array last-index lvalue warns';

done_testing;
