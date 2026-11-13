use strict;
use warnings;
use Scalar::Util qw(weaken);
use Test::More;

{
    package PromiseLoopModel;

    our $SINGLETON;

    sub new { bless {next_tick => [], running => 0}, shift }
    sub singleton { $SINGLETON ||= __PACKAGE__->new }
    sub next_tick { push @{$_[0]{next_tick}}, $_[1]; return }
    sub stop { $_[0]{running} = 0 }

    sub start {
        my $self = shift;
        local $self->{running} = 1;
        while ($self->{running}) {
            my $callback = shift @{$self->{next_tick}}
                or die 'no pending callback';
            do {
                $callback->($self);
            } while $callback = shift @{$self->{next_tick}};
        }
    }
}

{
    package PromiseModel;

    sub new {
        my $self = bless {resolve => [], reject => []}, shift;
        $self->{ioloop} = PromiseLoopModel->singleton;
        Scalar::Util::weaken($self->{ioloop});
        return $self;
    }

    sub ioloop { $_[0]{ioloop} }
    sub clone { ref($_[0])->new }
    sub reject { shift->_settle(reject => @_) }
    sub resolve { shift->_settle(resolve => @_) }
    sub catch { shift->then(undef, shift) }

    sub then {
        my ($self, $resolve, $reject) = @_;
        my $new = $self->clone;
        $self->{handled} = 1;
        push @{$self->{resolve}}, sub { _then_cb($new, $resolve, 'resolve', @_) };
        push @{$self->{reject}}, sub { _then_cb($new, $reject, 'reject', @_) };
        $self->_defer if $self->{results};
        return $new;
    }

    sub wait {
        my $self = shift;
        my $loop = $self->ioloop;
        return if $loop->{running};
        my $done;
        $self->_finally(0, sub { $done++; $loop->stop })->catch(sub { });
        $loop->start until $done;
        return;
    }

    sub _defer {
        my $self = shift;
        return unless my $results = $self->{results};
        my $callbacks = $self->{$self->{status}};
        @{$self}{qw(cycle resolve reject)} = (undef, [], []);
        $self->ioloop->next_tick(sub { $_->(@$results) for @$callbacks });
    }

    sub _finally {
        my ($self, $handled, $finally) = @_;
        my $new = $self->clone;
        my $cb = sub {
            my @results = @_;
            $new->resolve($finally->())->then(sub { @results });
        };
        my $before = $self->{handled};
        $self->catch($cb);
        my $next = $self->then($cb);
        delete $self->{handled} if !$before && !$handled;
        return $next;
    }

    sub _settle {
        my ($self, $status, @results) = @_;
        $self = $self->new unless ref $self;
        if (!$self->{results}) {
            @{$self}{qw(results status)} = (\@results, $status);
            $self->_defer;
        }
        return $self;
    }

    sub _then_cb {
        my ($new, $cb, $method, @results) = @_;
        return $new->$method(@results) unless defined $cb;
        my @res;
        return $new->reject($@) unless eval { @res = $cb->(@results); 1 };
        return $new->resolve(@res);
    }

    sub DESTROY {
        my $self = shift;
        warn "Unhandled rejected promise: $self->{results}[0]\n"
            if $self->{status} && $self->{status} eq 'reject' && !$self->{handled};
    }
}

my @warnings;
local $SIG{__WARN__} = sub { push @warnings, shift };

PromiseModel->reject('two')->then(sub { })->wait;

like(
    $warnings[0] // '',
    qr/Unhandled rejected promise: two/,
    'discarded rejected chain warns during wait cleanup'
);
is($warnings[1], undef, 'only one unhandled-rejection warning is delivered');

done_testing();
