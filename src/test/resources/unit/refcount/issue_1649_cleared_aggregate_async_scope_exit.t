use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken isweak);

BEGIN {
    package Future;
    our $VERSION = '0.52';
    $INC{'Future.pm'} = __FILE__;

    sub done { bless { state => 'done', values => [ @_[1 .. $#_ ] ] }, $_[0] }
    sub new { bless { state => 'pending', values => [], callbacks => [] }, $_[0] }
    sub AWAIT_NEW_DONE { shift->done(@_) }
    sub AWAIT_NEW_FAIL { bless { state => 'failed', values => [ @_ ] }, $_[0] }
    sub AWAIT_CLONE { ref($_[0])->new }
    sub AWAIT_IS_READY { $_[0]{state} ne 'pending' }
    sub AWAIT_IS_CANCELLED { $_[0]{state} eq 'cancelled' }
    sub AWAIT_GET { die $_[0]{values}[0] if $_[0]{state} eq 'failed'; return $_[0]{values}[0] }
    sub AWAIT_ON_READY { my ($self, $cb) = @_; $self->AWAIT_IS_READY ? $cb->($self) : push(@{$self->{callbacks}}, $cb); $self }
    sub AWAIT_CHAIN_CANCEL { $_[0] }
    sub AWAIT_DONE { my $self = shift; $self->{state} = 'done'; $self->{values} = [ @_ ]; my @callbacks = @{$self->{callbacks}}; $self->{callbacks} = []; $_->($self) for @callbacks; $self }
    sub AWAIT_FAIL { my $self = shift; $self->{state} = 'failed'; $self->{values} = [ @_ ]; my @callbacks = @{$self->{callbacks}}; $self->{callbacks} = []; $_->($self) for @callbacks; $self }
    sub AWAIT_WAIT { $_[0]->AWAIT_GET }
}

use Future::AsyncAwait;

our $destroyed = 0;
our $weak;
our $pending = Future->new;

{
    package Repro::Root;
    sub DESTROY { ++$main::destroyed }
}

async sub discard_cleared_root {
    my $root = bless { child => [1] }, 'Repro::Root';
    $weak = $root;
    weaken($weak);
    $root->{child} = [];
    await $main::pending;
}

my $future = discard_cleared_root();
ok(defined($weak) && isweak($weak), 'suspended async frame retains its weakly observed root');
$pending->AWAIT_DONE(1);
$future->AWAIT_GET;
ok(!defined($weak), 'weak observer clears when async lexical root exits');
is($destroyed, 1, 'root DESTROY runs exactly once');
done_testing;
