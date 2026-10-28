use strict;
use warnings;

use Test::More tests => 6;

use Hash::Util qw(fieldhash);

fieldhash my %object_fields;

my $object = {};
$object_fields{$object} = 'value';

is $object_fields{$object}, 'value',
    'Hash::Util exports fieldhash and stores values for reference keys';

my $other_object = {};
ok !exists $object_fields{$other_object},
    'fieldhash entries are associated with reference identity';

fieldhash my %mixed_fields;
$mixed_fields{plain_key} = 'plain value';
$mixed_fields{$object} = 'reference value';

is $mixed_fields{plain_key}, 'plain value',
    'fieldhash retains ordinary scalar-key behavior';
ok scalar(grep { $_ eq 'plain_key' } keys %mixed_fields),
    'fieldhash iteration includes ordinary scalar keys';
is $mixed_fields{$object}, 'reference value',
    'reference-key fieldhash values remain independent from scalar keys';

ok defined(&fieldhash), 'fieldhash is imported into the caller';
