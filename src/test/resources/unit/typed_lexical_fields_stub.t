use strict;
use warnings;
use Test::More;

my main $record;
sub FIELDS;

ok eval { $$record{field}; 1 }, 'a FIELDS sub stub does not impose field validation on a hash element';
ok eval { @$record{qw(first second)}; 1 }, 'a FIELDS sub stub does not impose field validation on a hash slice';

done_testing;
