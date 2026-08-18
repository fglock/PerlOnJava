use strict;
use warnings;
no warnings 'experimental::vlb';
use Test::More;

sub compile_error {
    my ($source) = @_;
    local $SIG{__WARN__} = sub {};
    eval "qr/$source/";
    return $@;
}

ok(eval(q{qr/(?<= a{253})z/}) && !$@,
    'lookbehind with analysed width 254 compiles');
ok(eval(q{qr/(?<= a{254})z/}) && !$@,
    'lookbehind with analysed width 255 compiles');
like(compile_error('(?<= a{255})z'),
    qr/^Lookbehind longer than 255 not implemented in regex/,
    'lookbehind with analysed width 256 is rejected');
like(compile_error('(?<= a{200}b{55})z'),
    qr/^Lookbehind longer than 255 not implemented in regex/,
    'compound fixed lookbehind counts every term');
like(compile_error('(?<= x{1000})z'),
    qr/^Lookbehind longer than 255 not implemented in regex/,
    'large fixed lookbehind reports the Perl ceiling');

done_testing;
