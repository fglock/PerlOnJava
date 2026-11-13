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

use Future::AsyncAwait;

our $destroyed = 0;
our $weak;
our $pending = Future->new;

{
    package Repro::CancelledRoot;
    sub DESTROY { ++$main::destroyed }
}

async sub hold_cancelled_root {
    my $root = bless { child => [1] }, 'Repro::CancelledRoot';
    $weak = $root;
    weaken($weak);
    await $main::pending;
}

my $future = hold_cancelled_root();
ok(defined($weak) && isweak($weak),
    'suspended async frame retains its weakly observed root before cancellation');
is($destroyed, 0, 'root is not destroyed while its async frame is suspended');
$future->AWAIT_CANCEL;
ok(!defined($weak), 'cancelling the returned Future releases its suspended lexical');
is($destroyed, 1, 'cancelled suspended root DESTROY runs exactly once');
$future->AWAIT_CANCEL;
is($destroyed, 1, 'repeated cancellation does not release the frame twice');
done_testing;
