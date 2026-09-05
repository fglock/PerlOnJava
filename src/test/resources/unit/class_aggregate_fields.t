use v5.36;
use feature 'class';
no warnings 'experimental::class';
use Test::More;

class AggregateFields {
    field @items;
    field %values;

    method set {
        @items = ('array');
        %values = (key => 'hash');
    }

    method check {
        return ($items[0], $values{key});
    }
}

my $object = AggregateFields->new;
$object->set;
is_deeply([$object->check], ['array', 'hash'],
    'aggregate class fields accept assignments');

done_testing;
