use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken isweak);

BEGIN {
    package Future;
    our $VERSION = '0.52';
    $INC{'Future.pm'} = __FILE__;

    sub done { bless { state => 'done', values => [ @_[1 .. $#_ ] ] }, $_[0] }
    sub new { bless { state => 'pending', values => [], callbacks => [], chained => [] }, $_[0] }
    sub AWAIT_NEW_DONE { shift->done(@_) }
    sub AWAIT_NEW_FAIL { bless { state => 'failed', values => [ @_ ] }, $_[0] }
    sub AWAIT_CLONE { ref($_[0])->new }
    sub AWAIT_IS_READY { $_[0]{state} ne 'pending' }
    sub AWAIT_IS_CANCELLED { $_[0]{state} eq 'cancelled' }
    sub AWAIT_GET { die $_[0]{values}[0] if $_[0]{state} eq 'failed'; die 'cancelled' if $_[0]{state} eq 'cancelled'; return $_[0]{values}[0] }
    sub AWAIT_ON_READY { my ($self, $cb) = @_; $self->AWAIT_IS_READY ? $cb->($self) : push(@{$self->{callbacks}}, $cb); $self }
    sub AWAIT_CHAIN_CANCEL { my ($self, $next) = @_; push(@{$self->{chained}}, $next); $self }
    sub AWAIT_DONE { my $self = shift; $self->{state} = 'done'; $self->{values} = [ @_ ]; my @callbacks = @{$self->{callbacks}}; $self->{callbacks} = []; $_->($self) for @callbacks; $self }
    sub AWAIT_FAIL { my $self = shift; $self->{state} = 'failed'; $self->{values} = [ @_ ]; my @callbacks = @{$self->{callbacks}}; $self->{callbacks} = []; $_->($self) for @callbacks; $self }
    sub AWAIT_CANCEL { my ($self) = @_; return $self if $self->{state} ne 'pending'; $self->{state} = 'cancelled'; my @callbacks = @{$self->{callbacks}}; $self->{callbacks} = []; $_->($self) for @callbacks; my @chained = @{$self->{chained}}; $self->{chained} = []; $_->AWAIT_CANCEL for @chained; return $self }
    sub AWAIT_WAIT { $_[0]->AWAIT_GET }
}

our ($destroyed, $weak_array, $weak_hash);
our ($standard_perl_destroyed, $standard_perl_weak);
our (@localized_array, %localized_hash);
our ($array_gate, $hash_gate, $cancel_gate) = map { Future->new } 1 .. 3;
$destroyed = 0;
$standard_perl_destroyed = 0;

{
    package Repro::LocalizedGlobalElement;
    sub DESTROY { ++$main::destroyed }
}

{
    package Repro::StandardPerlElement;
    sub DESTROY { ++$main::standard_perl_destroyed }
}

sub check_standard_perl_localized_array_lifetime {
    local @main::localized_array =
        (bless {}, 'Repro::StandardPerlElement');
    $main::standard_perl_weak = $main::localized_array[0];
    weaken($main::standard_perl_weak);
    ok(defined($main::standard_perl_weak) && isweak($main::standard_perl_weak),
        'standard Perl local array strongly owns its element');
    is($main::standard_perl_destroyed, 0,
        'standard Perl does not destroy a localized array element in scope');
}

sub check_standard_perl_localized_hash_lifetime {
    local %main::localized_hash = (
        value => bless({}, 'Repro::StandardPerlElement'));
    $main::standard_perl_weak = $main::localized_hash{value};
    weaken($main::standard_perl_weak);
    ok(defined($main::standard_perl_weak) && isweak($main::standard_perl_weak),
        'standard Perl local hash strongly owns its element');
    is($main::standard_perl_destroyed, 1,
        'standard Perl does not destroy a localized hash element in scope');
}

check_standard_perl_localized_array_lifetime();
ok(!defined($standard_perl_weak),
    'standard Perl releases the localized array element at scope exit');
is($standard_perl_destroyed, 1,
    'standard Perl localized array element DESTROY runs once');
check_standard_perl_localized_hash_lifetime();
ok(!defined($standard_perl_weak),
    'standard Perl releases the localized hash element at scope exit');
is($standard_perl_destroyed, 2,
    'standard Perl localized hash element DESTROY runs once');

my $async_source = <<'ASYNC_PERL';
use Future::AsyncAwait;

async sub hold_localized_array_element {
    local @main::localized_array =
        (bless { kind => 'array' }, 'Repro::LocalizedGlobalElement');
    $main::weak_array = $main::localized_array[0];
    weaken($main::weak_array);
    await $main::array_gate;
}

async sub hold_localized_hash_element {
    local %main::localized_hash = (
        value => bless({ kind => 'hash' }, 'Repro::LocalizedGlobalElement'));
    $main::weak_hash = $main::localized_hash{value};
    weaken($main::weak_hash);
    await $main::hash_gate;
}

async sub hold_cancelled_localized_array_element {
    local @main::localized_array =
        (bless { kind => 'cancelled array' }, 'Repro::LocalizedGlobalElement');
    $main::weak_array = $main::localized_array[0];
    weaken($main::weak_array);
    await $main::cancel_gate;
}
1;
ASYNC_PERL

my $async_compiled = eval $async_source;

SKIP: {
    skip 'async sub syntax is supported only by the PerlOnJava frontend', 13
        unless $async_compiled;

    my $array_future = hold_localized_array_element();
    ok(defined($weak_array) && isweak($weak_array),
        'localized global array element survives while async frame is suspended');
    is($destroyed, 0, 'localized global array element is not destroyed while suspended');
    $array_gate->AWAIT_DONE(1);
    $array_future->AWAIT_GET;
    ok(!defined($weak_array), 'localized global array element releases after resume');
    is($destroyed, 1, 'localized global array element DESTROY runs once');

    my $hash_future = hold_localized_hash_element();
    ok(defined($weak_hash) && isweak($weak_hash),
        'localized global hash element survives while async frame is suspended');
    is($destroyed, 1, 'localized global hash element is not destroyed while suspended');
    $hash_gate->AWAIT_DONE(1);
    $hash_future->AWAIT_GET;
    ok(!defined($weak_hash), 'localized global hash element releases after resume');
    is($destroyed, 2, 'localized global hash element DESTROY runs once');

    my $cancel_future = hold_cancelled_localized_array_element();
    ok(defined($weak_array) && isweak($weak_array),
        'localized global array element survives before async cancellation');
    is($destroyed, 2, 'localized global array element remains alive before cancellation');
    $cancel_future->AWAIT_CANCEL;
    ok(!defined($weak_array), 'cancellation releases the localized global array element');
    is($destroyed, 3, 'cancelled localized array element DESTROY runs once');
    $cancel_future->AWAIT_CANCEL;
    is($destroyed, 3, 'repeated cancellation does not release the localized owner twice');
}

done_testing;
