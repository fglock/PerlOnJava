use strict;
use warnings;
use Test::More;

ok('abc' =~ /(a)(b)(c)/, 'capture source matches');
is_deeply([@{^CAPTURE}], [qw(a b c)], '@{^CAPTURE} exposes all numbered buffers');
is(${^CAPTURE}[0], 'a', 'zero index aliases capture one');
is(${^CAPTURE}[2], 'c', 'positive indexing reaches the final capture');
is(${^CAPTURE}[-1], 'c', 'negative indexing counts from the final capture');
ok(exists ${^CAPTURE}[0], 'exists sees a populated capture index');
ok(exists ${^CAPTURE}[-1], 'exists supports a negative populated index');
ok(!exists ${^CAPTURE}[3], 'exists rejects an index beyond the capture array');

my $write_ok = eval q{ ${^CAPTURE}[0] = 'changed'; 1 };
ok(!$write_ok, '@{^CAPTURE} elements are read-only');
like($@, qr/(?:read-only|Modification of a read-only value)/i,
    'capture-array assignment reports the read-only contract');
is(${^CAPTURE}[0], 'a', 'failed assignment leaves the capture unchanged');

ok('0' =~ /(0)(x)?/, 'false and undef capture source matches');
is(${^CAPTURE}[0], '0', 'false string capture remains defined');
ok(!defined ${^CAPTURE}[1], 'nonparticipating capture is undef');
my $zero_write = eval q{ ${^CAPTURE}[0] = 'changed'; 1 };
ok(!$zero_write, 'false string capture remains read-only');
my $undef_write = eval q{ ${^CAPTURE}[1] = 'changed'; 1 };
ok(!$undef_write, 'undef capture remains read-only');

done_testing;
