use strict;
use warnings;

use Test::More;
use Scalar::Util qw(weaken);

{
    package Issue1794Transaction;

    sub new { bless { events => {} }, shift }

    sub on {
        my ($self, $name, $callback) = @_;
        push @{$self->{events}{$name}}, $callback;
        return $self;
    }

    sub finish { shift->emit('finish') }

    sub emit {
        my ($self, $name, @args) = @_;
        $_->($self, @args) for @{$self->{events}{$name} // []};
        return $self;
    }
}

{
    package Issue1794Controller;
    use Scalar::Util qw(weaken);

    sub new {
        my ($class, $tx) = @_;
        my $self = bless { tx => $tx }, $class;
        weaken($self->{tx});
        return $self;
    }

    sub on {
        my ($self, $name, $callback) = @_;
        my $tx = $self->{tx};
        return $tx->on($name => sub { shift; $self->$callback(@_) });
    }

    sub DESTROY { $main::destroyed++ }
}

our $destroyed = 0;
my $weak_controller;
{
    my $tx = Issue1794Transaction->new;
    my $controller = Issue1794Controller->new($tx);
    $weak_controller = $controller;
    weaken($weak_controller);

    $controller->on(finish => sub { shift });
    $tx->finish;
    undef $controller;

    is($destroyed, 0, 'transaction callback keeps controller alive before teardown');
    undef $tx;
}

is($destroyed, 1, 'controller DESTROY runs when its transaction releases callbacks');
is($weak_controller, undef, 'controller weak reference clears after transaction teardown');

done_testing;
