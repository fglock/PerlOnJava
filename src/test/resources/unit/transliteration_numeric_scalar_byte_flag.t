use strict;
use warnings;
use Test::More;
use Encode qw(is_utf8);

my $numeric = 232;
is($numeric =~ tr/.//d, 0, 'transliteration reports no matches');
is($numeric, '232', 'transliteration stringifies the numeric scalar');
ok(!is_utf8($numeric), 'transliteration keeps numeric stringification unflagged');

my $non_destructive = 232 =~ tr/.//dr;
is($non_destructive, '232', 'non-destructive transliteration returns the stringified value');
ok(!is_utf8($non_destructive), 'non-destructive transliteration keeps numeric stringification unflagged');

done_testing();
