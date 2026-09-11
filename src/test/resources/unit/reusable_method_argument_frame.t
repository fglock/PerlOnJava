use strict;
use warnings;
use Test::More;

{
    package ReusableMethodFrame;
    no warnings 'once';
    sub new { bless { x => 0 }, shift }
    *add = sub {
        my ($self, $n) = @_;
        $self->{x} += $n;
        return $self->{x};
    };
    *recurse = sub {
        my ($self, $n) = @_;
        return $n ? $self->recurse($n - 1) + 1 : 0;
    };
    *mutates_argument = sub {
        my ($self, $n) = @_;
        $_[1] = 99;
        return $n;
    };
}

my $object = ReusableMethodFrame->new;
is($object->add(3), 3, 'immediate lexical unpack method receives its argument');
is($object->add(4), 7, 'repeated method calls retain independent results');
is($object->recurse(8), 8, 'recursive immediate-unpack method retains nested frames');

my $argument = 5;
is($object->mutates_argument($argument), 5, 'initial lexical copy preserves argument value');
is($argument, 99, 'later @_ access keeps ordinary aliasing fallback');

done_testing;
