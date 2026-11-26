package Issue2118::UnicodePropertyRuntimePackage;

use strict;
use warnings;
use feature 'unicode_strings';

sub IsEOL { "0000\t0000\n000A\t000D\n2028\t2029\n" }

sub fold {
    local $_ = 'word';
    my $unremarkable_re = qr/[^\p{IsEOL}\e](?!\cH)/;
    my $max = '';
    my $grapheme_boundary = '';
    return s{\A(
        \e+
      |
        ${unremarkable_re}{1,$max}+
        ${unremarkable_re}*? $grapheme_boundary
    )}{}x;
}

1;
