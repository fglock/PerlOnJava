use strict;
use warnings;

use Test::More;
use B qw(svref_2object);
use List::Util qw(pairkeys pairvalues pairs unpairs);

{
    package POJListUtilPairHelpersRefcount;
    sub new { bless {}, shift }
}

my $object = POJListUtilPairHelpersRefcount->new;
is svref_2object($object)->REFCNT, 1, 'object starts with one owner';

my @pairs = ($object, 'value');
is svref_2object($object)->REFCNT, 2, 'source pair list owns one reference';

my @keys = pairkeys @pairs;
is svref_2object($object)->REFCNT, 3, 'pairkeys result owns one reference';
undef @keys;
is svref_2object($object)->REFCNT, 2, 'dropping pairkeys result releases its owner';

my @values = pairvalues ('key', $object);
is svref_2object($object)->REFCNT, 3, 'pairvalues result owns one reference';
undef @values;
is svref_2object($object)->REFCNT, 2, 'dropping pairvalues result releases its owner';

my @pair_refs = pairs $object, 'value';
is svref_2object($object)->REFCNT, 3, 'pairs result owns one nested reference';
undef @pair_refs;
is svref_2object($object)->REFCNT, 2, 'dropping pairs result releases nested owner';

my $pair = [$object, 'value'];
is svref_2object($object)->REFCNT, 3, 'array pair owns one reference';

my @flat = unpairs $pair;
is svref_2object($object)->REFCNT, 4, 'unpairs result owns one reference';
undef @flat;
is svref_2object($object)->REFCNT, 3, 'dropping unpairs result releases its owner';

done_testing;
