use strict;
use warnings;
use Test::More tests => 19;
use Scalar::Util qw(refaddr);

{
    package StableHashMethod::Counter;

    sub add {
        my ($self, $amount) = @_;
        $self->{x} += $amount;
        $self->{y} += $amount;
        return $self->{x} + $self->{y};
    }

    sub capture_args {
        my ($self, $value) = @_;
        return \@_;
    }
}

{
    package StableHashMethod::DynamicCounter;

    our $DELEGATE = { x => 10, y => 20 };

    sub add {
        my ($self, $amount) = @_;
        $self->{x} += $amount;
        $self->{y} += $amount;
        return $self->{x} + $self->{y};
    }
}

{
    package StableHashMethod::OverloadedCounter;

    our %STORAGE;
    use Scalar::Util qw(refaddr);
    use overload '%{}' => sub { $STORAGE{refaddr($_[0])} }, fallback => 1;

    sub new {
        my ($class, $data) = @_;
        my $self = bless {}, $class;
        $STORAGE{refaddr($self)} = $data;
        return $self;
    }

    sub add {
        my ($self, $amount) = @_;
        $self->{x} += $amount;
        $self->{y} += $amount;
        return $self->{x} + $self->{y};
    }
}

my $counter = bless { x => 1, y => 2 }, 'StableHashMethod::Counter';
my $x_alias = \$counter->{x};

is($counter->add(2), 7, 'simple method returns the updated field sum');
is($counter->{x}, 3, 'method updates the first hash field in place');
is($counter->{y}, 4, 'method updates the second hash field in place');
is($$x_alias, 3, 'existing field aliases observe in-place method updates');

$counter->{x} = 1000;
$counter->{y} = 2000;
is($counter->add(3), 3006, 'method handles fields assigned between calls');
is($counter->{x}, 1003, 'first reassigned field is updated');
is($counter->{y}, 2003, 'second reassigned field is updated');

delete $counter->{x};
$counter->{x} = 20;
is($counter->add(1), 2025, 'method handles a deleted and recreated hash field');
is($counter->{x}, 21, 'recreated field is updated in place');

my @list_result = $counter->add(1);
is_deeply(\@list_result, [2027], 'list context preserves the one-value method result');
$counter->add(2);
is($counter->{x}, 24, 'void context still applies the first field update');
is($counter->{y}, 2007, 'void context still applies the second field update');

my $overloaded = StableHashMethod::OverloadedCounter->new({ x => 5, y => 8 });
is($overloaded->add(2), 17,
        'hash dereference overload remains active for an eligible method body');
is_deeply($StableHashMethod::OverloadedCounter::STORAGE{refaddr($overloaded)},
        { x => 7, y => 10 }, 'overloaded method updates the delegated hash');

my $dynamic = bless { x => 1, y => 2 }, 'StableHashMethod::DynamicCounter';
is($dynamic->add(1), 5, 'method runs before a hash dereference overload is installed');
eval q{
    package StableHashMethod::DynamicCounter;
    use overload '%{}' => sub { $StableHashMethod::DynamicCounter::DELEGATE }, fallback => 1;
    1;
} or die $@;
is($dynamic->add(2), 34, 'method observes a hash dereference overload added later');
is_deeply($StableHashMethod::DynamicCounter::DELEGATE, { x => 12, y => 22 },
        'method updates the newly delegated hash');

my $captured_args = $counter->capture_args(45);
$counter->add(1);
is_deeply($captured_args, [$counter, 45],
        'a later call cannot mutate an escaped argument array');
is($captured_args->[1], 45, 'escaped argument values remain stable after reuse');
