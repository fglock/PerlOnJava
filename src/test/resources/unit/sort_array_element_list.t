use strict;
use warnings;
use Test::More;

my @audit = ([ 'unused', 'script' ], [ 'unused', 'bin' ]);

package SortArrayElementList;
package main;

my @sorted = sort $audit[0][1], $audit[1][1];
is_deeply(\@sorted, [ 'bin', 'script' ],
    'sort treats multidimensional lexical array elements as list items');

done_testing();
