use feature 'say';
use strict;
use Test::More;
use warnings;

{
    package StringOnly;
    use overload
        '""' => \&as_string,
        fallback => 1;

    sub new {
        my ($class, $value) = @_;
        return bless { value => $value }, $class;
    }

    sub as_string {
        my $self = shift;
        return $self->{value};
    }
}

{
    package NumericOnly;
    use overload
        '0+' => \&as_number,
        fallback => 1;

    sub new {
        my ($class, $value) = @_;
        return bless { value => $value }, $class;
    }

    sub as_number {
        my $self = shift;
        return $self->{value};
    }
}

# Create test objects
my $str_obj = StringOnly->new(42);
my $num_obj = NumericOnly->new(42);

# Test StringOnly class - defined string operation
ok(!("$str_obj" ne "42"), 'string overload works');

# Test StringOnly class - fallback numeric
ok(!((0 + $str_obj) != 42), 'numeric fallback works on StringOnly');

# Test NumericOnly class - defined numeric operation
ok(!((0 + $num_obj) != 42), 'numeric overload works');

# Test NumericOnly class - fallback string
my $num_str = "$num_obj";
ok(!($num_str ne "42"), 'string fallback works on NumericOnly (got: \'$num_str\')');

# Test in operations
my $sum = 10 + $str_obj;
ok(!($sum != 52), 'numeric fallback works in addition');

# Test string concatenation
my $concat = "Value: " . $num_obj;
ok(!($concat ne "Value: 42"), 'string fallback works in concatenation (got: \'$concat\')');

done_testing();
