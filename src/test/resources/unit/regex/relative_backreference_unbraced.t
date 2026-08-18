use strict;
use warnings;
use Test::More;

my $simple = 'barbar';
ok($simple =~ /(bar)\g-1/, 'unbraced relative backreference matches preceding capture');
is($1, 'bar', 'unbraced relative backreference preserves capture one');

my $nested = 'abbaab';
ok($nested =~ /((a)(b))\g-1\g-2\g-3/,
    'unbraced relative backreferences count nested captures');
is_deeply([$1, $2, $3], ['ab', 'a', 'b'],
    'nested capture numbering remains unchanged');

my $multiple = 'abccba';
ok($multiple =~ /(a)(b)(c)\g-1\g-2\g-3/,
    'multiple unbraced relative backreferences count backwards');
is_deeply([$1, $2, $3], ['a', 'b', 'c'],
    'multiple captures remain visible after relative backreferences');

ok('abab' =~ /(a)(b)\g1\g2/,
    'unbraced absolute backreferences remain supported');
ok('abba' =~ /(a)(b)\g{-1}\g{-2}/,
    'braced relative backreferences remain supported');
ok('abcdefghija' =~ /(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)\g-10/,
    'unbraced relative backreference parses the complete multi-digit distance');

sub compile_error {
    my ($source) = @_;
    local $SIG{__WARN__} = sub {};
    eval "qr/$source/";
    return $@;
}

like(compile_error('(a)\\g-0'), qr/^Reference to invalid group 0 in regex/,
    'relative group zero is rejected');
like(compile_error('(a)\\g-2'),
    qr/^Reference to nonexistent or unclosed group in regex/,
    'relative reference before the first capture is rejected');

my $qr_bar_relative = qr/(bar)\g-1/;
like('foobarbarxyz', $qr_bar_relative,
    'compiled relative-backreference pattern matches');
like('foobarbarxyz', qr/foo${qr_bar_relative}xyz/,
    'compiled relative-backreference pattern interpolates after literals');
like('foobarbarxyz', qr/(foo)${qr_bar_relative}xyz/,
    'interpolation does not renumber captures inside compiled pattern');
like('foobarbarxyz', qr/(foo)(bar)\g{-1}xyz/,
    'braced relative control matches the preceding capture');
like('foobarbarxyz', qr/(foo${qr_bar_relative})xyz/,
    'compiled relative pattern works inside an outer capture');
like('foobarbarxyz', qr/(foo(bar)\g{-1})xyz/,
    'braced relative control works inside an outer capture');

done_testing;
