use strict;
use warnings;
use Test::More;

my %values;
my $tie = tie $values{entry}, 'DeleteTiedHashScalar';
my $deleted = delete $values{entry};

is($DeleteTiedHashScalar::fetches, 1, 'deleting a tied scalar fetches its value once');
is($deleted, 'fetched value', 'delete returns the fetched value');
ok(!defined tied $values{entry}, 'the deleted hash element is no longer tied');

done_testing;

package DeleteTiedHashScalar;
our $fetches = 0;

sub TIESCALAR { return bless {}, shift; }
sub FETCH { ++$fetches; return 'fetched value'; }
sub STORE { return; }
