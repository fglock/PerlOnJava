package bytes;

our $hint_bits = 0x00000008;

sub import {
    $^H |= $hint_bits;
}

sub unimport {
    $^H &= ~$hint_bits;
}

1;
