use strict;
use warnings;
use Benchmark;

{ package Foo;
  sub new {
      my ($class) = @_;
      return bless { x => 1, y => 2 }, $class;
  }
  sub add {
      my ($self, $k) = @_;
      $self->{x} += $k;
      $self->{y} += $k;
      return $self->{x} + $self->{y};
  }
}

my $o = Foo->new();
my $sink = 0;

sub loop_method_call {
    my $i = 0;
    while ($i < 20000) {
        $sink += $o->add(1);
        $i++;
    }
}

timethis(500, sub { $sink = 0; loop_method_call() });
print "done $sink\n";
