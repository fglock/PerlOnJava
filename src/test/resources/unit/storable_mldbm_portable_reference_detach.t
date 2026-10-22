use strict;
use warnings;
use Test::More tests => 4;
use Storable qw(nfreeze thaw);

# MLDBM's portable Storable serializer freezes a scalar reference at STORE
# time and dereferences the restored value at FETCH time.
my %serialized;
my $source = [17];
$serialized{g} = nfreeze(\$source);
$source = '';

my $restored = thaw(delete $serialized{g});
is(ref($restored), 'REF', 'portable serialization restores MLDBM scalar envelope');
is(ref($$restored), 'ARRAY', 'restored envelope still contains an array reference');
is($$restored->[0], 17, 'changing the original reference does not change stored value');
is($source, '', 'source reference was replaced after serialization');
