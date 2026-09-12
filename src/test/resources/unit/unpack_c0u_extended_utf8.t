#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;

is_deeply(
    [unpack 'C0U*', "\xF8\x88\x80\x80\x80\xFC\x84\x80\x80\x80\x80"],
    [0x200000, 0x4000000],
    'C0U decodes Perl extended five- and six-byte UTF-8 sequences',
);

is_deeply(
    [unpack 'C0U*', "\xED\xA0\x80\xED\xBF\xBF"],
    [0xD800, 0xDFFF],
    'C0U preserves surrogate scalars',
);

my @warnings;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    my @decoded = unpack 'C0U*', "\xC0\xAF";
}
is(scalar @warnings, 1, 'C0U reports one overlong-sequence warning');
like($warnings[0], qr/overlong/, 'C0U warning identifies an overlong sequence');

done_testing;
