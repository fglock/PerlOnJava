use strict;
use warnings;
use Test::More tests => 2;

my $ok = eval q{
    sub cannot_localize_lexical {
        my $value = 'before';
        local $value = 'during';
    }
    cannot_localize_lexical();
};

ok(!$ok, 'localizing a lexical is a compile error');
like($@, qr/^Can't localize lexical variable \$value at /,
    'the lexical-localization diagnostic identifies the variable');
