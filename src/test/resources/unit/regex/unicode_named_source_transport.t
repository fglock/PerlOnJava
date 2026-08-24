use strict;
use warnings;
use charnames ':full';
use Test::More;

my $keycap = "9\x{FE0F}\x{20E3}";
my $sequence = qr/\N{KEYCAP DIGIT NINE}/;
like($keycap, qr/\A$sequence\z/,
    'generated multi-code-point named sequence matches as one atom');
unlike("9\x{FE0F}", qr/\A$sequence\z/,
    'named sequence requires every generated code point');

like('A', qr/\A\N{LATIN CAPITAL LETTER A}\z/,
    'ordinary named character still resolves');

my $number_sign = qr/\A\N{NUMBER SIGN}(a)\z/x;
ok('#a' =~ $number_sign,
    'NUMBER SIGN remains an atom rather than an /x comment introducer');
is($1, 'a', 'capture following NUMBER SIGN survives /x parsing');

unlike("$sequence", qr/POJSEQ/,
    'qr stringification does not expose an internal transport token');

my $class = qr/[x\N{KEYCAP DIGIT NINE}]/;
like($keycap, $class, 'named sequence is legal in an ordinary class');
like('x', $class, 'ordinary class alternatives remain available');

{
    local $SIG{__WARN__} = sub { };
    eval q{qr/(?[\N{KEYCAP DIGIT NINE}])/};
}
my $extended_prefix = '\N{} here is restricted to one character in regex;';
like($@, qr/^\Q$extended_prefix\E/,
    'named sequence remains illegal in a native extended class');

eval q{qr/\N{REGEX IMPLEMENTATION UNKNOWN NAME}/};
like($@, qr/Unknown charname 'REGEX IMPLEMENTATION UNKNOWN NAME'/,
    'unknown named character keeps the Perl diagnostic');

eval q{qr/\N{}/};
like($@, qr/^Unknown charname ''/,
    'empty named character keeps the Perl diagnostic');

done_testing;
