use strict;
use warnings;
use feature qw(class refaliasing);
use Test::More tests => 1;

no warnings 'experimental::class';
no warnings 'experimental::refaliasing';

class RefAliasField {
    field %items : reader;

    method rebind {
        \%items = \%ENV;
        $self;
    }
}

my $object = RefAliasField->new;
$object->rebind;
my $readable = eval { $object->items; 1 };
ok($readable, 'aggregate field reader remains usable after reference aliasing');
