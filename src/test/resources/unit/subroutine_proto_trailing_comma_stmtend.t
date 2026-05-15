use strict;
use warnings;
use Test::More;

# Trailing comma before statement end, separated by newline (see DBIx::Class
# t/storage/deprecated_exception_source_bind_attrs.t). Perl allows this for
# parenthesis-free multi-arg prototype calls.

sub proto22 ($$) { "$_[0]|$_[1]" }

is(
    scalar do {
        proto22 11, 22,
        ;
    },
    '11|22',
    'trailing comma before semicolon across newline after prototype args'
);

# Same shape as Test::Exception::throws_ok (&$;$)
sub proto_block_ok (&$;$) { 1 }

ok(
    scalar do {
        proto_block_ok { 1 } qr/^$/,
            'third arg with trailing comma',
        ;
    },
    'ampersand-first prototype: trailing comma newline semicolon (throws_ok shape)'
);

done_testing;
