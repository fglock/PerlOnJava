use feature 'say';
use strict;
use warnings;

print "1..6\n";

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
print "not " if "$str_obj" ne "42";
say "ok # string overload works";

# Test StringOnly class - fallback numeric
print "not " if (0 + $str_obj) != 42;
say "ok # numeric fallback works on StringOnly";

# Test NumericOnly class - defined numeric operation
print "not " if (0 + $num_obj) != 42;
say "ok # numeric overload works";

# Test NumericOnly class - fallback string
my $num_str = "$num_obj";
print "not " if $num_str ne "42";
say "ok # string fallback works on NumericOnly (got: '$num_str')";

# Test in operations
my $sum = 10 + $str_obj;
print "not " if $sum != 52;
say "ok # numeric fallback works in addition";

# Test string concatenation
my $concat = "Value: " . $num_obj;
print "not " if $concat ne "Value: 42";
say "ok # string fallback works in concatenation (got: '$concat')";
