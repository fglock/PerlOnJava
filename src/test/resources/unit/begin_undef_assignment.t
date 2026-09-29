use strict;
use warnings;
use Test::More tests => 1;

my $result = qx{$^X -e 'BEGIN { undef = 0 }' 2>&1};
like($result,
    qr/^Can't modify undef operator in scalar assignment at -e line 1, near "0 \}"\nBEGIN not safe after errors--compilation aborted at -e line 1\.\n\z/,
    'an illegal assignment in BEGIN uses Perl\'s compile-error phaser diagnostic');
