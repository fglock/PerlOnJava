use v5.36;
use feature 'class';
no warnings 'experimental::class';
use Test::More;

class ScalarAggregateReaderContext {
    field @items :reader = qw(one two three);
    field %values :reader = (key => 'value');
}

my $object = ScalarAggregateReaderContext->new;
is(scalar $object->items, 3, 'array reader observes scalar context');
is(scalar $object->values, 1, 'hash reader observes scalar context');
is_deeply([$object->items], [qw(one two three)], 'array reader preserves list context');
is_deeply([$object->values], ['key', 'value'], 'hash reader preserves list context');

done_testing;
