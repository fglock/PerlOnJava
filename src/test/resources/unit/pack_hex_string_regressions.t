use strict;
use warnings;

use Test::More;

sub packed_hex {
    my ($template, $value) = @_;
    my $packed = pack($template, $value);
    return (length($packed), unpack('H*', $packed));
}

is_deeply([packed_hex('H16', 0)], [8, '0000000000000000'],
    'fixed-width H padding shares the final partial byte');
is_deeply([packed_hex('H4', 'f')], [2, 'f000'],
    'H pads a short odd-nibble input to the requested width');
is_deeply([packed_hex('H3', '')], [2, '0000'],
    'H pads an empty input to a rounded-up odd width');
is_deeply([packed_hex('H16', 'very very very very loooong salt')],
    [8, 'feb20feb20feb20f'],
    'H uses Perl nibble folding for non-hex characters');
is_deeply([packed_hex('h16', 'very very very very loooong salt')],
    [8, 'ef2bf0be02ef2bf0'],
    'h uses the same folded nibbles in low-first order');
is_deeply([packed_hex('H*', 'very')], [2, 'feb2'],
    'star width consumes all folded input nibbles');

done_testing;
