package List::Util::PP;

use strict;
use warnings;
use Exporter 'import';

our @EXPORT_OK = qw(sum product);

sub sum (@) {
    return undef unless @_;
    my $sum = 0;
    $sum += $_ foreach @_;
    return $sum;
}

sub product (@) {
    my $product = 1;
    $product *= $_ foreach @_;
    return $product;
}

1;
