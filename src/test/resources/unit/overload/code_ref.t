use strict;
use warnings;
use Test::More tests => 10;

package MyCode {

    sub new {
        my ( $class, $code ) = @_;

        # Ensure we store a real coderef, not another object
        die "Data must be a coderef" unless ref $code eq 'CODE';
        return bless [ $code ], $class;
    }

    use overload '&{}' => sub {
        my $self = shift;
        return $self->[0];
    };
}

# Test 1: Object creation
my $obj = MyCode->new( sub { return "Hello" } );
ok( ref $obj, 'Object created' );

# Test 2: Basic dereferencing and calling
is( $obj->(), "Hello", 'Dereference and call using ->() returns correct value' );

# Test 3: Alternative dereference syntax
is( &$obj(), "Hello", 'Dereference using &$obj() returns correct value' );

# Test 4: Passing arguments to the code
my $adder = MyCode->new( sub { my ($a, $b) = @_; return $a + $b } );
is( $adder->(5, 3), 8, 'Passing arguments to dereferenced code works' );

# Test 5: Code with closure
my $counter = 0;
my $increment = MyCode->new( sub { return ++$counter } );
is( $increment->(), 1, 'First call increments counter' );
is( $increment->(), 2, 'Second call increments counter again' );

# Test 6: Code returning multiple values
my $multi = MyCode->new( sub { return (1, 2, 3) } );
my @result = $multi->();
is_deeply( \@result, [1, 2, 3], 'Code returning list works correctly' );

# Test 7: Code in scalar context
my $scalar_result = $multi->();
is( $scalar_result, 3, 'Code in scalar context returns last value' );

# Test 8: Code that modifies its arguments
my $modifier = MyCode->new( sub { $_[0] .= " modified" } );
my $str = "original";
$modifier->($str);
is( $str, "original modified", 'Code can modify its arguments' );

# Test 9: Nested code reference objects
my $nested = MyCode->new( sub { 
    my $inner = MyCode->new( sub { return "nested" } );
    return $inner->();
});
is( $nested->(), "nested", 'Nested code reference objects work' );

