use strict;
use warnings;

use Test::More;
use Scalar::Util qw(weaken);

{
    package POJReturnedOwnedScalarSequence;

    sub new {
        bless { callbacks => [] }, shift;
    }

    sub then {
        my $self = shift;
        my $seq = __PACKAGE__->new;
        push @{ $self->{callbacks} }, [ $seq ];
        Scalar::Util::weaken $self->{callbacks}[-1][0];
        return $seq;
    }

    sub on_ready {
        my $self = shift;
        return $self;
    }
}

{
    package POJReturnedOwnedScalarMutex;

    sub new {
        bless { queue => [] }, shift;
    }

    sub enter {
        my $self = shift;
        my $down = POJReturnedOwnedScalarSequence->new;
        push @{ $self->{queue} }, $down;
        my $retf = $down->then->on_ready;
        return $retf;
    }
}

sub collect {
    my @items = @_;
    return \@items;
}

my $mutex = POJReturnedOwnedScalarMutex->new;
my $held = collect(
    $mutex->enter,
    $mutex->enter,
    $mutex->enter,
);

ok defined $mutex->{queue}[0]{callbacks}[0][0],
    'weak callback target survives direct chained return into argument list';
is scalar(@$held), 3, 'collector holds returned sequence objects';

done_testing;
