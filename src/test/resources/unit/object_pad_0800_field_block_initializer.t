use strict;
use warnings;
use Test::More;

my $loaded = eval q{
    use Object::Pad 0.800;
    class ObjectPad0800FieldBlockInitializer {
        field $prefix :param;
        field $derived { $prefix . '-derived' }
        field @items { 'first', 'second' }
        field %mapping { answer => 'forty-two' }
        method value { $derived }
        method item_values { @items }
        method answer { $mapping{answer} }
    }
    1;
};
if (!$loaded && $@ =~ /Can't locate Object\/Pad\.pm/) {
    plan skip_all => 'Object::Pad is not installed in system Perl';
}
die $@ unless $loaded;

is(ObjectPad0800FieldBlockInitializer->new(prefix => 'first')->value,
    'first-derived', 'field initializer blocks evaluate with the new instance');
is(ObjectPad0800FieldBlockInitializer->new(prefix => 'second')->value,
    'second-derived', 'field initializer blocks run for each constructed instance');
is_deeply([ObjectPad0800FieldBlockInitializer->new(prefix => 'items')->item_values],
    ['first', 'second'], 'array field initializer blocks preserve list context');
is(ObjectPad0800FieldBlockInitializer->new(prefix => 'hash')->answer,
    'forty-two', 'hash field initializer blocks preserve key/value context');

done_testing();
