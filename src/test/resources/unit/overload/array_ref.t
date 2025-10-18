use strict;
use warnings;
use Test::More tests => 5;

package MyArray {

    sub new {
        my ( $class, $data ) = @_;
        return bless { data => $data }, $class;
    }

    # Overload @{}
    use overload '@{}' => sub {
        my $self = shift;
        return $self->{data};
    };

}

# Test 1: Object stringifies to array ref
my $obj = MyArray->new( [ 1, 2, 3 ] );
ok( ref $obj, 'Object created' );

# Test 2: Can dereference as array
is_deeply( [@$obj], [ 1, 2, 3 ], 'Dereference using @$obj returns correct array' );

# Test 3: Scalar context of array dereference
is( scalar @$obj, 3, 'Scalar context of @$obj gives correct length' );

# Test 4: Modify underlying data affects dereference
$obj->{data} = [ 4, 5 ];
is_deeply( [@$obj], [ 4, 5 ], 'Changing internal data changes array dereference' );

# Test 5: Empty array
my $empty_obj = MyArray->new( [] );
is_deeply( [@$empty_obj], [], 'Dereference empty array' );

