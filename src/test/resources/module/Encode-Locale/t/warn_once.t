use strict;
use warnings;
use Test::More tests => 2;

my @warns;
BEGIN { $SIG{__WARN__} = sub { push @warns, @_ } }

use Encode::Locale;

BEGIN {
    use Encode;
    my $a = encode("UTF-8", "foo\xFF");
    ok $a, "UTF-8 encoding works after loading Encode::Locale";
}

is "@warns", "", 'no warnings';
