use strict;
use warnings;
use Test::More tests => 4;

my $value = chr 0xCA;
utf8::upgrade $value;

is length($value), 1, 'upgraded Latin-1 value is one character';
{
    use bytes;
    is length($value), 2, 'upgraded Latin-1 value exposes two UTF-8 bytes';
    my @octets = map { ord } split //, $value;
    is scalar(@octets), 2, 'split under use bytes returns one field per octet';
    is_deeply \@octets, [0xC3, 0x8A],
        'split under use bytes preserves the UTF-8 octet values';
}
