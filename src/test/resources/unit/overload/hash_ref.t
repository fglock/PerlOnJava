use strict;
use warnings;
use Test::More tests => 6;

package MyHash {

    sub new {
        my ( $class, $data ) = @_;

        # Ensure we store a real hashref, not another object
        die "Data must be a hashref" unless ref $data eq 'HASH';
        return bless [ $data ], $class;
    }

    use overload '%{}' => sub {
        my $self = shift;
        return $self->[0];
    };
}

# Test 1: Object creation
my $obj = MyHash->new( { a => 1, b => 2 } );
ok( ref $obj, 'Object created' );

# Test 2: Dereference returns expected hash
is_deeply( {%$obj}, { a => 1, b => 2 }, 'Dereference using %$obj returns correct hash' );

# Test 3: Accessing a key directly
is( $obj->{b}, 2, 'Accessing hash key via -> operator works' );

# Test 4: Modify internal data affects dereference
$obj->{c} = 3;
is_deeply( {%$obj}, { a => 1, b => 2, c => 3 }, 'Modifying internal hash reflects in dereference' );

# Test 5: Empty hash case
my $empty_obj = MyHash->new( {} );
is_deeply( {%$empty_obj}, {}, 'Dereference empty hash' );

# Test 6: Scalar context of hash dereference (returns number of key/value pairs)
my @keys = keys %$obj;
is( scalar( keys %$obj ), 3, 'Scalar context of keys on dereferenced hash gives correct count' );

