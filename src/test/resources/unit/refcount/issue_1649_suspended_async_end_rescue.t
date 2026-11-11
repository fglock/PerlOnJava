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
    sub AWAIT_CANCEL {
        my ($self) = @_;
        return $self if $self->{state} ne 'pending';
        $self->{state} = 'cancelled';
        my @callbacks = @{$self->{callbacks}};
        $self->{callbacks} = [];
        $_->($self) for @callbacks;
        my @chained = @{$self->{chained}};
        $self->{chained} = [];
        $_->AWAIT_CANCEL for @chained;
        return $self;
    }
    sub AWAIT_WAIT { $_[0]->AWAIT_GET }
}

our ($destroyed, $weak, $pending, $returned, $async_ready);
our %rescue_registry;
$destroyed = 0;
$async_ready = 0;
$pending = Future->new;

{
    package Repro::SuspendedEndRescue;
    sub DESTROY {
        ++$main::destroyed;
        # Resurrect once through a weakened package aggregate slot. The async
        # lexical takes ownership and deletes this package slot before await.
        $main::rescue_registry{root} = $_[0]
            if $main::destroyed == 1;
    }
}

END {
    if ($async_ready) {
        ok(defined($weak), 'END sees the DESTROY-rescued object through the suspended lexical');
        ok(isweak($weak), 'END observer remains weak while the async frame is pending');
        is($destroyed, 1, 'DESTROY rescued the object exactly once before suspension');
        ok(!exists($rescue_registry{root}), 'no package strong slot remains for the rescued object');
        is($pending->{state}, 'pending', 'the awaited Future remains pending through END');
        is($returned->{state}, 'pending', 'the Future returned by the async sub remains pending through END');
        $returned->AWAIT_CANCEL;
        $pending->AWAIT_CANCEL;
        done_testing();
    }
}

my $async_source = <<'ASYNC_PERL';
use Future::AsyncAwait;

async sub retain_rescued_root_until_end {
    my $held;
    my $root = bless {}, 'Repro::SuspendedEndRescue';
    $main::weak = $root;
    weaken($main::weak);

    $main::rescue_registry{root} = $root;
    weaken($main::rescue_registry{root});
    undef $root;

    $held = $main::rescue_registry{root};
    delete $main::rescue_registry{root};
    await $main::pending;
}

$main::returned = retain_rescued_root_until_end();
1;
ASYNC_PERL

my $compiled = eval $async_source;
if (!$compiled) {
    plan skip_all => 'Future::AsyncAwait async syntax is unavailable to this Perl frontend';
}
$async_ready = 1;
