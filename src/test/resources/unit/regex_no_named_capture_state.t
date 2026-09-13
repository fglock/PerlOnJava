use strict;
use warnings;
use Test::More tests => 5;

'name=perl' =~ /name=(?<language>\w+)/;
is($+{language}, 'perl', 'named capture is initially published');
is_deeply($-{language}, ['perl'], 'named capture alternatives are initially published');

my $text = 'one two';
pos($text) = 0;
ok($text =~ /one/g, 'scalar global match without captures succeeds');
ok(!exists $+{language}, 'successful no-capture global match clears %+');
ok(!exists $-{language}, 'successful no-capture global match clears %-');
