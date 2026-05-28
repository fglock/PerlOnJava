use strict;
use warnings;

use Test::More;
use Test2::Tools::Refcount qw(is_refcount is_oneref);

sub add_callback {
    my ($callbacks, $guard) = @_;
    push @$callbacks, do {
        my $ref = $guard;
        sub { $ref = $ref };
    };
}

sub run_and_clear_callbacks {
    my ($callbacks) = @_;
    my @todo = @$callbacks;
    @$callbacks = ();
    $_->() for @todo;
    return;
}

my $guard = {};
my @callbacks;

is_oneref($guard, 'guard starts with one lexical owner');
add_callback(\@callbacks, $guard);
is_refcount($guard, 2, 'stored callback captures unblessed guard');

run_and_clear_callbacks(\@callbacks);
is_oneref($guard, 'discarded callback releases unblessed captured guard');

done_testing;
