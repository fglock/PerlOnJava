use strict;
use warnings;
use Test::More tests => 2;
use Storable qw(nfreeze thaw);

{
    package Local::StorableExportParent;
    sub freeze { return 'parent freeze method'; }
}

{
    package Local::StorableExportChild;
    our @ISA = ('Local::StorableExportParent');
    use Storable;
}

my $object = bless {}, 'Local::StorableExportChild';
is($object->freeze, 'parent freeze method',
    'default Storable imports do not shadow inherited freeze methods');

my $copy = thaw(nfreeze([{ value => 17 }]));
is($copy->[0]{value}, 17, 'optional Storable exports remain importable');
