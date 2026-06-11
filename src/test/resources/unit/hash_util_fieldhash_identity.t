use strict;
use warnings;

use Test::More tests => 5;
use Hash::Util::FieldHash qw(fieldhash id);

fieldhash my %fields;

my $re1 = qr/foo/;
my $re2 = qr/foo/;

isnt id($re1), id($re2), 'distinct regex refs have distinct ids';

$fields{$re1} = 'first';
$fields{$re2} = 'second';

is $fields{$re1}, 'first', 'first regex ref keeps its own field value';
is $fields{$re2}, 'second', 'second regex ref keeps its own field value';

{
    my $obj = bless {}, 'FieldHashIdentityObject';
    $fields{$obj} = 'object';
    ok exists $fields{$obj}, 'scoped object key exists before destruction';
}

is_deeply [ sort values %fields ], [qw(first second)], 'destroyed object key is pruned';
