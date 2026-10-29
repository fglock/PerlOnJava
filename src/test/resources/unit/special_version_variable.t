use strict;
use warnings;
use Test::More;

{
    plan skip_all => 'Perl 5.8.1 or better is required to test this' unless ( $] >= 5.008001 );
    plan tests => 2;
    ok($] >= 5.008001, '$] supports the standard Perl version comparison');
    my $supported = 1 unless ($] < 5.008001);
    ok($supported, '$] parses in a parenthesized unless condition');
}
