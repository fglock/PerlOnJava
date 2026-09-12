use strict;
use warnings;
use Test::More;
use Devel::LexAlias qw(lexalias);

{
    package DirectArgumentCopyTiedObserver;

    sub TIEHASH {
        bless { data => $_[1], replacement => $_[2] }, $_[0];
    }

    sub FETCH {
        return $_[0]{data}{$_[1]};
    }

    sub STORE {
        my ($self, $key, $value) = @_;
        Devel::LexAlias::lexalias(1, '$n', \$self->{replacement});
        $self->{data}{$key} = $value;
    }
}

sub update_and_observe {
    my ($self, $n) = @_;
    $self->{x} += $n;
    return $n;
}

my %storage;
tie my %tied, 'DirectArgumentCopyTiedObserver', \%storage, 91;
is(update_and_observe(\%tied, 4), 91,
    'tied hash callback can replace the active lexical copy');
is($storage{x}, 4,
    'the store receives the value calculated before the lexical rebinding');

done_testing;
