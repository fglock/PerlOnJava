use strict;
use warnings;
use Test::More;

{
    no warnings 'regexp';
    ok('a{b' =~ /\Aa{b\z/,
        'an unclosed non-quantifier opening brace is literal');
    ok('a{word}b' =~ /\Aa{word}b\z/,
        'non-numeric braces after an atom are literal');
    ok('a{1,2,3}' =~ /\Aa{1,2,3}\z/,
        'too many comma fields make braces literal');
    ok('a}b' =~ /\Aa}b\z/, 'an unmatched closing brace is literal');
}

ok('aa' =~ /\Aa{2}\z/, 'exact quantifier remains valid');
ok('aaa' =~ /\Aa{2,3}\z/, 'bounded quantifier remains valid');
ok('aa' =~ /\Aa{,2}\z/, 'omitted-minimum quantifier remains valid');

sub compile_error {
    my ($source) = @_;
    local $SIG{__WARN__} = sub {};
    eval "qr/$source/";
    return $@;
}

TODO: {
    local $TODO = 'Joni quantifier diagnostic parity';
    like(compile_error('a{2}{3}'), qr/^Nested quantifiers in regex/,
        'adjacent brace quantifiers are rejected');
    like(compile_error('a{2}?+'), qr/^Nested quantifiers in regex/,
        'conflicting lazy and possessive suffixes are rejected');
    like(compile_error('a{01}'), qr/^Invalid quantifier in \{,\} in regex/,
        'a leading-zero count is rejected');
}

done_testing;
