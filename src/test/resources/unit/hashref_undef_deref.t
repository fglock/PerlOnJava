use strict;
use warnings;
use Test::More;

# Regression test: ExifTool frequently does:
#   my $dataPt = $$dirInfo{DataPt};
# where $dirInfo is a hash reference and DataPt is expected to be a SCALAR ref.
# This must remain true, otherwise later code like `length $$dataPt` will die.

my $buff = "abc";
my %dirInfo = ( DataPt => \$buff );
my $dirInfo = \%dirInfo;

is(ref($dirInfo), 'HASH', '$dirInfo is a HASH ref');

my $dataPt = $$dirInfo{DataPt};

ok(defined $dataPt, '$dataPt is defined');
is(ref($dataPt), 'SCALAR', '$dataPt is a SCALAR ref');
is($$dataPt, 'abc', 'dereferencing $dataPt yields the expected string');
is(length $$dataPt, 3, 'length $$dataPt works');

done_testing();
