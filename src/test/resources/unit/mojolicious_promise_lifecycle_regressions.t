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

    sub DESTROY { }
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
    my $source = PromiseLifecycleChained->new;
    $source->then;

    isa_ok(
        $source->{callbacks}[0]->(),
        'PromiseLifecycleLoop',
        'callback closure keeps discarded chained promise alive'
    );
};

done_testing();
