use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);

# Regression for issue #1175.  Algorithm::SlidingWindow keeps a preallocated
# circular buffer in a blessed hash and clears occupied slots through a local
# array-reference alias.  Those stores must release the slot's strong edge.

{
    package SWR_Object;
    sub new { bless { id => $_[1] }, $_[0] }
    sub DESTROY { push @SWR_Object::destroyed, $_[0]->{id} }
}

sub make_window {
    my ($capacity) = @_;
    my @buf;
    $#buf = $capacity - 1;
    my $self = bless { _buf => \@buf, _capacity => $capacity,
                       _head => 0, _size => 0 }, 'SWR_Window';
    return $self;
}

sub SWR_Window::add {
    my $self = $_[0];
    return $self if @_ == 1;
    my $cap = $self->{_capacity};
    my $buf = $self->{_buf};
    my $head = $self->{_head};
    my $size = $self->{_size};
    for (my $ai = 1; $ai < @_; $ai++) {
        my $item = $_[$ai];
        if ($size == $cap) {
            my $old = $buf->[$head];
            $buf->[$head] = undef;
            $head++;
            $head = 0 if $head == $cap;
        } else {
            $size++;
        }
        my $tail = $head + $size - 1;
        $tail -= $cap if $tail >= $cap;
        $buf->[$tail] = $item;
    }
    $self->{_head} = $head;
    $self->{_size} = $size;
    return $self;
}

sub SWR_Window::clear {
    my ($self) = @_;
    my $buf = $self->{_buf};
    for my $i (0 .. $self->{_capacity} - 1) {
        $buf->[$i] = undef;
    }
    $self->{_size} = 0;
}

{
    my $object = SWR_Object->new('direct');
    my $weak = $object;
    my @buffer = ($object);
    weaken($weak);
    undef $object;
    $buffer[0] = undef;
    ok(!defined($weak), 'direct array slot overwrite releases the referent');
}

{
    my $window = make_window(3);
    my $object = SWR_Object->new('sparse');
    my $weak = $object;
    weaken($weak);
    my $buf = $window->{_buf};
    $buf->[1] = $object;
    undef $object;
    $buf->[1] = undef;
    ok(!defined($weak), 'preallocated sparse slot releases the referent');
}

{
    my $window = make_window(2);
    my $object = SWR_Object->new('nested');
    my $weak = $object;
    weaken($weak);
    my $buf = $window->{_buf};
    $buf->[0] = $object;
    undef $object;
    $buf->[0] = undef;
    ok(!defined($weak), 'blessed-hash array alias releases the referent');
}

{
    my $window = make_window(2);
    my $first = SWR_Object->new('evicted');
    my $weak_first = $first;
    weaken($weak_first);
    my $second = SWR_Object->new('current');
    my $weak_second = $second;
    weaken($weak_second);
    $window->add($first, $second);
    ok(defined($weak_first), 'first circular-buffer item is live');
    ok(defined($weak_second), 'second circular-buffer item is live');
    undef $first;
    $window->add(SWR_Object->new('replacement'));
    ok(!defined($weak_first), 'circular-buffer eviction clears old slot');
    undef $second;
    $window->clear;
    ok(!defined($weak_second), 'clear loop releases current slot');
}

{
    # Keep this block close to Algorithm::SlidingWindow::refs.t: multiple
    # aliased arguments followed by a direct bless temporary.
    my $w = make_window(2);
    my $obj1 = bless({}, 'SWR_NoDestroy');
    my $weak1 = $obj1;
    weaken($weak1);
    my $obj2 = bless({}, 'SWR_NoDestroy');
    my $weak2 = $obj2;
    weaken($weak2);
    $w->add($obj1, $obj2);
    undef $obj1;
    $w->add(bless({}, 'SWR_NoDestroy'));
    ok(!defined($weak1), 'CPAN-shaped eviction clears the first object');
    undef $obj2;
    $w->clear;
    ok(!defined($weak2), 'CPAN-shaped clear clears the second object');
}

{
    my $window = make_window(1);
    my $object = SWR_Object->new('destroy');
    my $weak = $object;
    weaken($weak);
    $window->{_buf}->[0] = $object;
    undef $object;
    $window->clear;
    ok(!defined($weak), 'DESTROY referent weak slot is cleared');
}
is(scalar @SWR_Object::destroyed, 7,
   'each released referent is destroyed exactly once');
my %destroyed;
$destroyed{$_}++ for @SWR_Object::destroyed;
is_deeply(\%destroyed,
          { direct => 1, sparse => 1, nested => 1, evicted => 1,
            current => 1, replacement => 1, destroy => 1 },
          'all released referents have exactly one DESTROY');

done_testing;
