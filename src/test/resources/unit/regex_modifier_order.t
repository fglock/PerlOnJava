use 5.010;
use strict;
use warnings;
use Test::More;
use re qw(regexp_pattern);

my ($pat, $mod) = regexp_pattern(qr/a/i);
is($mod, 'i', 'use 5.010 does not add implicit unicode modifier');
is("$pat", 'a', 'regexp_pattern returns pattern');

($pat, $mod) = regexp_pattern(qr/a/ui);
is($mod, 'ui', 'regexp_pattern keeps Perl modifier order for explicit /ui');
is("$pat", 'a', 'regexp_pattern still returns pattern for /ui');
is(qr/a/ui, '(?^ui:a)', 'regex stringification keeps Perl modifier order for /ui');

done_testing();
