use strict;
use warnings;
use Test::More;

{
    package Local::RegexScalarHolder;

    use overload '""' => sub { 'regex assertion' };

    sub new {
        my $class = shift;
        my $regex = shift;
        return bless \$regex, $class;
    }

    sub do_assertion {
        my $self = shift;
        my $target = shift;
        return $target =~ $$self;
    }
}

my $arguments = ['fixed', qr/x/];
my $holder = Local::RegexScalarHolder->new($arguments->[1]);

is(ref($holder), 'Local::RegexScalarHolder',
    'blessing a reference to a copied regex scalar blesses the scalar reference');
ok($holder->do_assertion($arguments->[0]),
    'a scalar reference to the regex can still perform a match');
is(ref($arguments->[1]), 'Regexp',
    'dereferencing the assertion does not bless the caller regex');
like('fixed', $arguments->[1],
    'the caller regex remains usable after blessing the scalar copy');

done_testing;
