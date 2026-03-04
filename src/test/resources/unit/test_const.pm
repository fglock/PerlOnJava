package TestConst;
use strict;
use warnings;
BEGIN {
    *HAVE_PERLIO = defined &PerlIO::get_layers ? sub() { 1 } : sub() { 0 };
}
sub test {
    my @layers = HAVE_PERLIO ? grep { 1 } ("a","b") : ();
    return join(",", @layers);
}
1;
