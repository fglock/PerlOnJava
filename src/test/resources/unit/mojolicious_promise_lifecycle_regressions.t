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

done_testing();
