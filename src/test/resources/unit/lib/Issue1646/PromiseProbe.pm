package Issue1646::PromiseProbe;

use strict;
use warnings;
use Scalar::Util qw(blessed);

our @TICKS;
our $LOOP_RUNNING = 0;
our $LOOP_STOPPED = 0;

sub new { bless { resolve => [], reject => [] }, shift }
sub clone { ref($_[0])->new }
sub catch { shift->then(undef, shift) }
sub reject { shift->_settle('reject', @_) }
sub resolve { shift->_settle('resolve', @_) }

sub then {
    my ($self, $resolve, $reject) = @_;
    my $next = $self->clone;
    $self->{handled} = 1;
    push @{$self->{resolve}}, sub { _then_cb($next, $resolve, 'resolve', @_) };
    push @{$self->{reject}}, sub { _then_cb($next, $reject, 'reject', @_) };
    $self->_defer if $self->{results};
    return $next;
}

sub wait {
    my $self = shift;
    return if $LOOP_RUNNING;
    my $done;
    $self->_finally(0, sub { $done++; $LOOP_STOPPED = 1 })->catch(sub { });
    _start_loop() until $done;
}

sub _start_loop {
    $LOOP_RUNNING = 1;
    $LOOP_STOPPED = 0;
    while (@TICKS && !$LOOP_STOPPED) {
        my $tick = shift @TICKS;
        $tick->();
        undef $tick;
    }
    $LOOP_RUNNING = 0;
}

sub _finally {
    my ($self, $handled, $finally) = @_;
    my $next = $self->clone;
    my $callback = sub {
        my @results = @_;
        $next->resolve($finally->())->then(sub { @results });
    };
    my $was_handled = $self->{handled};
    $self->catch($callback);
    my $returned = $self->then($callback);
    delete $self->{handled} if !$was_handled && !$handled;
    return $returned;
}

sub _defer {
    my $self = shift;
    return unless my $results = $self->{results};
    my $callbacks = $self->{status} eq 'resolve' ? $self->{resolve} : $self->{reject};
    @{$self}{qw(cycle resolve reject)} = (undef, [], []);
    push @TICKS, sub { $_->(@$results) for @$callbacks };
}

sub _settle {
    my ($self, $status, @results) = @_;
    if (blessed($results[0]) && $results[0]->can('then') && $status eq 'resolve') {
        $results[0]->then(sub { $self->resolve(@_) }, sub { $self->reject(@_) });
    } elsif (!$self->{results}) {
        @{$self}{qw(results status)} = (\@results, $status);
        $self->_defer;
    }
    return $self;
}

sub _then_cb {
    my ($next, $callback, $method, @results) = @_;
    return $next->$method(@results) unless defined $callback;
    my @values;
    return $next->reject($@) unless eval { @values = $callback->(@results); 1 };
    return $next->resolve(@values);
}

sub DESTROY {
    my $self = shift;
    return if $self->{handled} || ($self->{status} // '') ne 'reject' || !$self->{results};
    warn "Unhandled rejected promise: @{$self->{results}}";
}

1;
