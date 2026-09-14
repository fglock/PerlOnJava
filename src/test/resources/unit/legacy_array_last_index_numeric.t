use strict;
use warnings;
use Test::More tests => 1;

my $error = do {
    eval q{$#0};
    $@;
};

like $error,
    qr/^\$# is no longer supported as of Perl 5\.30 at \(eval \d+\) line 1\.\n?$/,
    'numeric operands cannot revive the removed $# syntax';
