package List::Util::PP;

use strict;
use warnings;
use Exporter 'import';

our @EXPORT_OK = qw(sum product pairgrep);

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

sub pairgrep (&@) {
    my $block = shift;
    return map {
        local $main::a = $_[$_];
        local $main::b = $_[$_ + 1];
        $block->() ? (wantarray ? @_[$_, $_ + 1] : 1) : ();
    } map { $_ * 2 } 0 .. int($#_ / 2);
}

1;
