use strict;
use warnings;
use Test::More tests => 12;

my $subject = '123456';
pos($subject) = 4;
is($subject =~ s/\d\d\G/7/g, 1, 'atoms before G match once');
is($subject, '12756', 'short replacement after atoms before G');

$subject = '123456';
pos($subject) = 4;
is($subject =~ s/\d\d\G/789/g, 1, 'long replacement matches once');
is($subject, '1278956', 'long replacement does not move the input anchor');

$subject = '123456';
pos($subject) = 4;
is($subject =~ s/\d\d(?=\d\G)/7/g, 1, 'lookahead observes independent G');
is($subject, '17456', 'lookahead replacement uses the earlier match start');

$subject = '123456';
pos($subject) = 4;
my $replacement = sub { '78' };
is($subject =~ s/\d\d\G/$replacement->()/eg, 1,
   'code replacement preserves independent G');
is($subject, '127856', 'code replacement result is applied once');

$subject = '123456';
pos($subject) = 4;
ok($subject =~ /\d\d\G/g, 'ordinary global match can begin before G');
is($&, '34', 'ordinary match ends at the external position');

$subject = "\x{3b1}12";
pos($subject) = 2;
ok($subject =~ /\d\G/g, 'G uses Perl character offsets on Unicode input');
is($&, '1', 'Unicode match begins before the independent G position');
