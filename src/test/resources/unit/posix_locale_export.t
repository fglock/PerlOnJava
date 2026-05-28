use strict;
use warnings;
use Test::More;

use POSIX;

my $locale = eval { setlocale(LC_ALL, 'POSIX') };
is($@, '', 'LC_ALL is exported by default');
is($locale, 'POSIX', 'setlocale accepts imported LC_ALL');
is(LC_ALL, POSIX::LC_ALL(), 'imported LC_ALL matches fully-qualified constant');

done_testing();
