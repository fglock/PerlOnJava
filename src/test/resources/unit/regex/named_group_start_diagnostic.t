use strict;
use warnings;
use Test::More tests => 1;

my $compiled = eval q{qr/(?<;name>match)/; 1};
ok(!$compiled && index($@, 'Group name must start with a non-digit word character') >= 0,
   'invalid named-group start uses the Perl diagnostic');
