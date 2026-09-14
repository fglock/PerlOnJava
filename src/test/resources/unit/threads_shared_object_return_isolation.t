use strict;
use warnings;
use Test::More tests => 2;
use threads;
use threads::shared;

{
    package ThreadSharedObjectReturnIsolation::Jar;
    my @jar :shared;

    sub new { bless(&threads::shared::share({}), shift) }
    sub store {
        my ($self, $cookie) = @_;
        push @jar, $cookie;
        return $jar[-1];
    }
    sub peek { $jar[-1] }
    sub fetch { pop @jar }
}

{
    package ThreadSharedObjectReturnIsolation::Cookie;
    sub new {
        my ($class, $type) = @_;
        my $self = bless(&threads::shared::share({}), $class);
        $self->{type} = $type;
        return $self;
    }
    sub DESTROY { delete shift->{type} }
}

package main;

my $jar = ThreadSharedObjectReturnIsolation::Jar->new();
my $cookie = ThreadSharedObjectReturnIsolation::Cookie->new('oatmeal');
$jar->store($cookie);
threads->create(sub {
    $jar->store(ThreadSharedObjectReturnIsolation::Cookie->new('raisin'));
})->join;

$cookie = $jar->fetch;
$cookie = $jar->fetch;
undef $cookie;
share($cookie);
$cookie = $jar->store(ThreadSharedObjectReturnIsolation::Cookie->new('vanilla'));

threads->create(sub {
    $cookie = ThreadSharedObjectReturnIsolation::Cookie->new('chocolate');
})->join;

is($cookie->{type}, 'chocolate', 'shared scalar receives child assignment');
is($jar->peek->{type}, 'vanilla', 'parent jar retains its independent object');
