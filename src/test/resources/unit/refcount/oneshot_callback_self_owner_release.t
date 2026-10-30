use strict;
use warnings;
use feature 'current_sub';
use Test::More tests => 5;
use Scalar::Util qw(weaken);

our $destroyed = 0;

{
    package OneShotOwner;

    sub new { bless {}, shift }
    sub touch { 1 }
    sub DESTROY { ++$main::destroyed }

    package OneShotEmitter;
    use Scalar::Util qw(weaken);

    sub new { bless { events => {} }, shift }

    sub on {
        push @{$_[0]{events}{$_[1]}}, $_[2];
        return $_[2];
    }

    sub once {
        my ($self, $name, $cb) = @_;
        weaken $self;
        my $wrapper = sub {
            $self->unsubscribe($name => __SUB__);
            $cb->(@_);
        };
        $self->on($name => $wrapper);
        return $wrapper;
    }

    sub unsubscribe {
        my ($self, $name, $cb) = @_;
        if ($cb) {
            $self->{events}{$name} = [grep { $cb ne $_ } @{$self->{events}{$name}}];
            delete $self->{events}{$name} unless @{$self->{events}{$name}};
        }
        else {
            delete $self->{events}{$name};
        }
        return $self;
    }

    sub subscribers { shift->{events}{shift()} //= [] }

    sub emit {
        my ($self, $name) = (shift, shift);
        if (my $subscribers = $self->{events}{$name}) {
            for my $cb (@$subscribers) { $self->$cb(@_) }
        }
        return $self;
    }
}

my $emitter = OneShotEmitter->new;
my ($weak_owner, $weak_wrapper);
{
    my $owner = OneShotOwner->new;
    $weak_owner = $owner;
    weaken($weak_owner);

    my $wrapper = $emitter->once(tick => sub { shift; $owner->touch });
    $weak_wrapper = $wrapper;
    weaken($weak_wrapper);
}

ok defined $weak_owner, 'one-shot callback keeps its captured owner before emission';
ok defined $weak_wrapper, 'one-shot callback wrapper is registered before emission';

$emitter->emit('tick');

is $destroyed, 1, 'removing an active self-referential callback releases its capture';
ok !defined $weak_owner, 'captured owner weak reference clears after one-shot emission';
ok !defined $weak_wrapper, 'one-shot wrapper weak reference clears after emission';
