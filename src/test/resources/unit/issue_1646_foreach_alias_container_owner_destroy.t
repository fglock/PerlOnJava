use strict;
use warnings;
use feature 'current_sub';
use Scalar::Util qw(weaken);
use Test::More;
our $destroyed = 0;
{
  package Issue1646::Emitter;
  sub emit {
    my ($self, $name) = @_;
    if (my $subscribers = $self->{events}{$name}) {
      for my $cb (@$subscribers) { $self->$cb(@_) }
    }
    return $self;
  }
  sub once {
    my ($self, $name, $cb) = @_;
    Scalar::Util::weaken($self);
    my $wrapper = sub { $self->unsubscribe($name => __SUB__); $cb->(@_) };
    push @{$self->{events}{$name}}, $wrapper;
    return $wrapper;
  }
  sub unsubscribe {
    my ($self, $name, $cb) = @_;
    $self->{events}{$name} = [grep { $_ ne $cb } @{$self->{events}{$name}}];
    delete $self->{events}{$name} unless @{$self->{events}{$name}};
    return $self;
  }
}
{
  package Issue1646::Owner;
  our @ISA = ('Issue1646::Emitter');
  sub DESTROY { $main::destroyed++ }
  sub write_chunk {
    my ($self, $chunk, $cb) = @_;
    $self->once(drain => sub { shift; $self->$cb(@_) }) if $cb;
    return $self;
  }
}
my $owner = bless {}, 'Issue1646::Owner';
my $weak = $owner;
weaken($weak);
$owner->write_chunk(P => sub {
  my $self = shift;
  $self->write_chunk(oll => sub { shift->write_chunk('') });
  $self->emit('drain');
});
$owner->emit('drain');
undef $owner;
is($destroyed, 1, 'owner destruction follows nested one-shot emission');
ok(!defined $weak, 'weak owner is cleared after nested emission');
done_testing;
