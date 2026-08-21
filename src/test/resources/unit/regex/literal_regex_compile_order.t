use strict;
use warnings;
use Test::More tests => 3;

my $error = do {
    local $@;
    eval q{ use re Debug => 'ALL'; qr{(?{a})(?<b>\g{c}}) };
    $@;
};

like($error, qr/Unmatched \( in regex/,
     'malformed literal regex is diagnosed before the later source token');
like($error, qr/m\/\(\?\{a\}\)\( <-- HERE \?<b>/,
     'diagnostic retains the original executable callback source');
unlike($error, qr/syntax error.*near/s,
       'later source syntax does not replace the regex diagnostic');
