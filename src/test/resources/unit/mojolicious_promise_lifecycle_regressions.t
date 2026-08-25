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

    sub reject {
        my $self = ref $_[0] ? shift : shift->new;
        $self->{error} = shift;
        $self->_defer;
        return $self;
    }

    sub then {
        my ($self, $resolve, $reject) = @_;
        my $next = __PACKAGE__->new;
        $self->{handled} = 1;
        push @{$self->{resolve}}, sub {
            $resolve ? $resolve->() : $next;
        };
        push @{$self->{reject}}, sub {
            $reject ? $reject->($self->{error}) : $next->reject($self->{error});
        };
        $self->_defer if $self->{error};
        return $next;
    }

    sub wait {
        my $self = shift;
        my $done;
        my $before = $self->{handled};
        $self->then(sub { $done++ }, sub { $done++ });
        delete $self->{handled} unless $before;
        PromiseWarningLoop::one_tick() until $done;
        return;
    }

    sub _defer {
        my $self = shift;
        my $callbacks = $self->{reject};
        $self->{resolve} = [];
        $self->{reject} = [];
        PromiseWarningLoop->next_tick(sub { $_->() for @$callbacks });
    }

    sub DESTROY {
        my $self = shift;
        warn "Unhandled rejected promise: $self->{error}\n"
            if $self->{error} && !$self->{handled};
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
