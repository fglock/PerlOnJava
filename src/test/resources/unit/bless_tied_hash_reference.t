use strict;
use warnings;
use Test::More tests => 3;
use Scalar::Util qw(blessed);

{
    package Local::BlessTiedHash;

    sub TIEHASH { bless { value => bless({}, 'Local::Original') }, shift }
    sub FETCH   { $_[0]{value} }
    sub STORE   { $_[0]{value} = $_[1] }
}

tie my %values, 'Local::BlessTiedHash';

my $object = bless $values{value}, 'Local::Reblessed';
ok($object, 'bless accepts a reference fetched from a tied hash element');
is(ref($object), 'Local::Reblessed', 'bless returns the reblessed fetched reference');
ok(blessed($object), 'the fetched reference remains blessed');
