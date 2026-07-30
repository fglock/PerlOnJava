use strict;
use warnings;

use Test::More tests => 2;

my $path = 'dir';
my $message = "Failed to change directory to “$path”: permission denied";

is(
    length($message),
    58,
    'valid UTF-8 bytes are accepted in an interpolated source string without use utf8',
);

is(
    unpack('H*', substr($message, 30, 3)),
    'e2809c',
    'source text without use utf8 preserves UTF-8 as octets',
);
