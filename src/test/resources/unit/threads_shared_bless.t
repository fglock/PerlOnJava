use strict;
use warnings;
use Test::More;

BEGIN {
    use Config;
    plan(skip_all => "Perl not compiled with 'useithreads'")
        unless $Config{'useithreads'};
}

plan tests => 10;

use threads;
use threads::shared;

my $value :shared = 42;
my $object = &threads::shared::bless(\$value, 'ThreadSharedBlessFixture');
is(ref($object), 'ThreadSharedBlessFixture',
    'threads::shared::bless blesses a shared reference');
is($$object, 42, 'threads::shared::bless preserves the shared value');

SKIP: {
    skip('threads::shared bless export requires version 1.74', 1)
        if $threads::shared::VERSION < 1.74;
    my $imported_value :shared = 7;
    my $imported_object = &bless(\$imported_value, 'ImportedThreadSharedBless');
    is(ref($imported_object), 'ImportedThreadSharedBless',
        'threads::shared exports bless when threads are enabled');
}

my $shared_hash :shared = shared_clone({});
threads->create(sub {
    threads::shared::bless($shared_hash, 'ThreadSharedReblessed');
})->join;
is(ref($shared_hash), 'ThreadSharedReblessed',
    'shared blessing propagates when reblessed by another thread');

my $empty_value :shared;
my ($empty_object, $warning);
{
    local $SIG{__WARN__} = sub { $warning .= $_[0] };
    $empty_object = &threads::shared::bless(\$empty_value, '');
}
is(ref($empty_object), 'main',
    'empty class name defaults to main and emits the standard warning');
like($warning, qr/Explicit blessing to '' \(assuming package main\)/,
    'threads::shared::bless preserves the empty-class warning');

eval { &threads::shared::bless($empty_value, []) };
like($@, qr/Attempt to bless into a reference/,
    'threads::shared::bless rejects a reference class name');

eval { &share(1) };
like($@, qr/Argument to share needs to be passed as ref/,
    'share rejects non-reference arguments');

SKIP: {
    skip('shared scalar glob diagnostics require threads::shared 1.74', 1)
        if $threads::shared::VERSION < 1.74;
    eval q{my $shared_value :shared; $shared_value = *ABC};
    like($@, qr/Invalid value for shared scalar/,
        'shared scalar rejects a glob value');
}

eval { &cond_signal(1) };
like($@, qr/Argument to cond_signal needs to be passed as ref/,
    'cond_signal rejects non-reference arguments');
