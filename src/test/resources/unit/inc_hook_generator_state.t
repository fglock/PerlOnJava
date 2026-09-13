use strict;
use warnings;
use Test::More tests => 10;

my $state = {
    chunks => [
        "package IncHook::GeneratorState;\nsub value { 42 }\n",
        "1;\n",
    ],
    calls => 0,
};

my $hook = sub {
    my ($self, $file) = @_;
    return unless $file eq 'IncHook/GeneratorState.pm';

    return (sub {
        my ($placeholder, $generator_state) = @_;
        is($placeholder, 0, 'generator receives the required placeholder argument');
        is($generator_state, $state, 'generator receives state returned after its coderef');
        ++$generator_state->{calls};
        $_ = shift @{ $generator_state->{chunks} } // '';
        return length $_;
    }, $state);
};

# The generator receives this by alias through @_ on each call; loading the
# module must not retain an extra state reference after those calls return.
my $initial_state_refcount = &Internals::SvREFCNT($state) + 1;

{
    local @INC = ($hook, @INC);
    ok(eval { require IncHook::GeneratorState; 1 }, 'require loads source from generator')
        or diag $@;
}

is($state->{calls}, 3, 'generator consumes both chunks and observes EOF');
is(IncHook::GeneratorState::value(), 42, 'generated module was compiled and loaded');
is(&Internals::SvREFCNT($state) + 1, $initial_state_refcount,
    'generator state is not retained after loading');
