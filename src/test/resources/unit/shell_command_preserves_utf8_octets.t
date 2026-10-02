use strict;
use warnings;
use Test::More;

ok(!utf8::is_utf8($^X), '$^X is an unflagged executable path');

my $octets = chr(256);
utf8::encode($octets);
my $command = "$^X -e 'print qq($octets)' | $^X -CI -e 'print ord(<STDIN>)'";
my $output = `$command`;
is($output, '256', 'shell command preserves UTF-8 octets between Perl processes');

done_testing();
