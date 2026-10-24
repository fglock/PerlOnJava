use strict;
use warnings;

use Scalar::Util qw(weaken);
use Test::More;

BEGIN {
    plan skip_all => 'Mojolicious is not installed'
        unless eval { require Mojo::EventEmitter; 1 };
}

our $destroyed = 0;

{
    package Issue1646::ReentrantCallbackOwner;
    our @ISA = ('Mojo::EventEmitter');

    sub DESTROY { $main::destroyed++ }

    sub write_chunk {
        my ($self, $chunk, $callback) = @_;
        $self->once(drain => sub { shift; $self->$callback(@_) }) if $callback;
        return $self;
    }
}

my $owner = bless {}, 'Issue1646::ReentrantCallbackOwner';
my $weak_owner = $owner;
weaken($weak_owner);
$owner->write_chunk('P' => sub {
    my $self = shift;
    $self->write_chunk('oll!' => sub { shift->write_chunk('') });
    $self->emit('drain');
});

ok(defined $weak_owner, 'registered callbacks retain their captured owner');
$owner->emit('drain');
undef $owner;
is($destroyed, 1, 'reentrant one-shot callbacks release captures after emission');
ok(!defined $weak_owner, 'captured owner weak reference clears after emission');

done_testing;
