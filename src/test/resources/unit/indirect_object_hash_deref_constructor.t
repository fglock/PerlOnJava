use strict;
use warnings;
use Test::More;

{
    package Issue1118::Constructor;

    sub new {
        my ($class, %args) = @_;
        return bless \%args, $class;
    }
}

my %options = (handle_params => { from_hash => 'preserved' });
my $object = new Issue1118::Constructor
    %{ $options{handle_params} },
    explicit => 'argument';

isa_ok($object, 'Issue1118::Constructor',
    'indirect constructor accepts a hash dereference as its first argument');
is($object->{from_hash}, 'preserved', 'hash dereference expands into constructor arguments');
is($object->{explicit}, 'argument', 'following named arguments are retained');

done_testing;
