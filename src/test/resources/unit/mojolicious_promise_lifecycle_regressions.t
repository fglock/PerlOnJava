use strict;
use warnings;
use feature 'state';

use Scalar::Util qw(isweak weaken);
use Test::More;

{
    package PromiseLifecycleLoop;

    sub new { bless {}, shift }
    sub DESTROY { }
}

{
    package PromiseLifecycleChained;

    our $destroyed = 0;

    sub new {
        my $class = ref($_[0]) || $_[0];
        return bless {}, $class;
    }

    sub ioloop {
        return
            exists $_[0]{ioloop}
            ? $_[0]{ioloop}
            : (
                ref($_[0]{ioloop} = main::loop_singleton())
                    && Scalar::Util::weaken($_[0]{ioloop}),
                $_[0]{ioloop}
            )
            if @_ == 1;
        ref($_[0]{ioloop} = $_[1]) && Scalar::Util::weaken($_[0]{ioloop});
        return $_[0];
    }

    sub clone { $_[0]->new->ioloop($_[0]->ioloop) }

    sub then {
        my $self = shift;
        my $next = $self->clone;
        push @{$self->{callbacks}}, sub { return $next->ioloop };
        return $next;
    }

    sub DESTROY { $destroyed++ }
}

{
    package PromiseWarningLoop;

    our @next_tick;

    sub next_tick { push @next_tick, $_[1] }
    sub one_tick  { shift(@next_tick)->() if @next_tick }
    sub drain     { one_tick() while @next_tick }
}

{
    package PromiseWarningTiming;

    sub new { bless {resolve => [], reject => []}, shift }

    sub reject  { shift->_settle(reject  => @_) }
    sub resolve { shift->_settle(resolve => @_) }
    sub catch   { shift->then(undef, shift) }

    sub then {
        my ($self, $resolve, $reject) = @_;
        my $next = __PACKAGE__->new;
        $self->{handled} = 1;
        push @{$self->{resolve}}, sub { _then_cb($next, $resolve, resolve => @_) };
        push @{$self->{reject}},  sub { _then_cb($next, $reject,  reject  => @_) };
        $self->_defer if $self->{results};
        return $next;
    }

    sub wait {
        my $self = shift;
        my $done;
        $self->_finally(0, sub { $done++ })->catch(sub { });
        PromiseWarningLoop::one_tick() until $done;
        return;
    }

    sub _defer {
        my $self = shift;
        return unless my $results = $self->{results};
        my $callbacks = $self->{$self->{status}};
        $self->{resolve} = [];
        $self->{reject} = [];
        PromiseWarningLoop->next_tick(sub { $_->(@$results) for @$callbacks });
    }

    sub _finally {
        my ($self, $handled, $finally) = @_;
        my $new = __PACKAGE__->new;
        my $cb = sub {
            my @results = @_;
            $new->resolve($finally->())->then(sub { @results });
        };
        my $before = $self->{handled};
        $self->catch($cb);
        my $next = $self->then($cb);
        delete $self->{handled} unless $before || $handled;
        return $next;
    }

    sub _settle {
        my ($self, $status, @results) = @_;
        $self = $self->new unless ref $self;
        if ($status eq 'resolve' && ref($results[0]) eq __PACKAGE__) {
            $results[0]->then(
                sub { $self->resolve(@_) },
                sub { $self->reject(@_) }
            );
        }
        elsif (!$self->{results}) {
            @{$self}{qw(results status)} = (\@results, $status);
            $self->_defer;
        }
        return $self;
    }

    sub _then_cb {
        my ($new, $cb, $method, @results) = @_;
        return $new->$method(@results) unless $cb;
        return $new->resolve($cb->(@results));
    }

    sub DESTROY {
        my $self = shift;
        warn "Unhandled rejected promise: $self->{results}[0]\n"
            if $self->{status} && $self->{status} eq 'reject' && !$self->{handled};
    }
}

sub loop_singleton {
    state $loop = PromiseLifecycleLoop->new;
    return $loop;
}

sub weak_default_attribute {
    my ($owner, $name, $factory) = @_;
    return $owner->{$name} if exists $owner->{$name};
    $owner->{$name} = $factory->($owner);
    weaken($owner->{$name});
    return $owner->{$name};
}

subtest 'weak promise attribute survives release of another singleton alias' => sub {
    my $promise = bless {}, 'PromiseLifecycleHolder';

    weak_default_attribute($promise, ioloop => sub { loop_singleton() });
    ok(isweak($promise->{ioloop}), 'promise attribute is weak');
    isa_ok($promise->{ioloop}, 'PromiseLifecycleLoop', 'weak singleton attribute');

    my $temporary_alias = loop_singleton();
    undef $temporary_alias;

    isa_ok(
        $promise->{ioloop},
        'PromiseLifecycleLoop',
        'state singleton keeps weak promise attribute alive after another alias is undefined'
    );
};

subtest 'closure retains discarded chained promise and its weak loop' => sub {
    $PromiseLifecycleChained::destroyed = 0;
    my $source = PromiseLifecycleChained->new;
    $source->then;

    isa_ok(
        $source->{callbacks}[0]->(),
        'PromiseLifecycleLoop',
        'callback closure keeps discarded chained promise alive'
    );

    is(
        $PromiseLifecycleChained::destroyed,
        0,
        'source and closure-retained chained promise remain alive'
    );
    undef $source;
    is(
        $PromiseLifecycleChained::destroyed,
        2,
        'discarded chained promise is destroyed with its retaining source'
    );

    $PromiseLifecycleChained::destroyed = 0;
    PromiseLifecycleChained->new->then;
    is(
        $PromiseLifecycleChained::destroyed,
        2,
        'entire discarded Promise chain is destroyed at the statement boundary'
    );
};

subtest 'discarded rejected chain warns before localized handler restoration' => sub {
    my @warnings;
    local $SIG{__WARN__} = sub { push @warnings, shift };

    PromiseWarningTiming->reject('discarded')->then(sub { })->wait;

    like(
        $warnings[0],
        qr/Unhandled rejected promise: discarded/,
        'unhandled rejection warning is delivered during wait cleanup'
    );
};

done_testing();
