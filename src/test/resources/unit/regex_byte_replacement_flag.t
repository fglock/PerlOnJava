use strict;
use warnings;

use Encode qw(is_utf8);
use Test::More tests => 4;

my $value = "¿≠";
my @capture_flags;

$value =~ s/([^[:alnum:][:space:]])/
    do {
        push @capture_flags, is_utf8($1) ? 1 : 0;
        "\\" . $1;
    }
/gex;

ok(!grep($_, @capture_flags), 'captures from a byte string remain bytes');
ok(!is_utf8($value), 'code replacement does not upgrade the byte string');

my $pattern = qr/^$value$/;
ok(!is_utf8("$pattern"), 'compiled pattern retains byte semantics');
ok("¿≠" =~ $pattern, 'byte pattern matches the original byte string');
