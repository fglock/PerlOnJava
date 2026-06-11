use strict;
use warnings;
use Test::More;

my $error = do {
    local $@;
    eval 'use strict; unknown_func;';
    $@;
};

like(
    $error,
    qr/Bareword "unknown_func" not allowed while "strict subs" in use/,
    'strict subs rejects a void-context bareword statement'
);

my $ok = eval 'no strict "subs"; harmless_bareword; 1';
is($ok, 1, 'non-strict void-context bareword remains a no-op');

done_testing();
