use strict;
use warnings;
use Test::More;

# An identifier followed by -> inside a braced dereference is an expression
# (a constant or sub call whose result is dereferenced), not a variable name.
use constant DEREF_TABLE => {
    list  => { args => [ 'a', 'b' ] },
    hash  => { key => 'value' },
    array => [ 'x', 'y' ],
};

my @args = @{ DEREF_TABLE->{list}->{args} };
is_deeply \@args, [ 'a', 'b' ],
    'a braced constant element dereferenced as an array is an expression';

my %inner = %{ DEREF_TABLE->{hash} };
is $inner{key}, 'value',
    'a braced constant element dereferenced as a hash is an expression';

my @first = @{ DEREF_TABLE->{array} };
is $first[1], 'y',
    'a braced constant array reference is an expression';

# A bare identifier in braces with no arrow still names the package variable.
our @plain_package = ( 'p', 'q' );
is scalar(@{ plain_package }), 2,
    'a braced bare identifier still names a package variable';

done_testing;
