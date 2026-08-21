use strict;
use warnings;
use Test::More tests => 1;

my $compiled = eval q{ qr/(?i-l:foo)/; 1 };
ok(!$compiled && index($@, 'Regexp modifier "l" may not appear after the "-"') >= 0,
   'inline locale modifier after minus uses the Perl diagnostic');
