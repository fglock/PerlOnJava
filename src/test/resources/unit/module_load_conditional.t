#!/usr/bin/env perl
use strict;
use warnings;
use Test::More tests => 4;

my $loaded = eval { require Module::Load::Conditional; 1 };
ok($loaded, 'Module::Load::Conditional loads')
    or diag $@;

my $hook_called = 0;
my $hook = sub {
    my (undef, $file) = @_;
    return unless $file eq 'Test/More.pm';

    $hook_called++;
    die "hidden $file\n";
};

my $result;
my $lived;
{
    local @INC = ($hook, @INC);
    local $Module::Load::Conditional::CACHE = {};

    $lived = eval {
        $result = Module::Load::Conditional::can_load(
            modules => {
                'Test::More' => undef,
            },
            nocache => 1,
        );
        1;
    };
}

ok(!$lived, 'dying @INC hook aborts Module::Load::Conditional lookup');
like($@, qr/hidden Test\/More\.pm/, 'hook error is preserved');
is($hook_called, 1, '@INC hook was consulted exactly once');
