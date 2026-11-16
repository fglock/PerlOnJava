use strict;
use warnings;
use Test::More;

%; = (marker => 42);
sub punctuation_hash_reference { return \%;; }
my $hashref = punctuation_hash_reference();
is(ref($hashref), 'HASH', 'reference to punctuation hash is parsed under strict');
is($hashref->{marker}, 42, 'reference points to the punctuation hash');

done_testing();
