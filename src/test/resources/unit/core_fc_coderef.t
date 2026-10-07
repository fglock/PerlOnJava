use strict;
use warnings;
use Test::More tests => 2;

my $fc = \&CORE::fc;
my $sharp_s = pack('C', 0xdf);
is($fc->($sharp_s), $sharp_s,
    'CORE::fc code reference uses byte semantics without unicode_strings');

{
    use feature 'unicode_strings';
    is($fc->($sharp_s), 'ss',
        'CORE::fc code reference honors caller unicode_strings feature');
}
