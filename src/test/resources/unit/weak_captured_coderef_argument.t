use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);

{
    package Local::Runner;

    sub run {
        my $self = shift;
        Scalar::Util::weaken(my $callback = shift);
        return $callback->();
    }
}

{
    package Local::Storage;

    sub invoke {
        my $self = shift;
        return Local::Runner->run(sub { $self->{value} });
    }
}

my $storage = bless { value => 23 }, 'Local::Storage';
is($storage->invoke(), 23,
    'a temporary captured coderef stays callable after its callee weakens a copy');

done_testing();
