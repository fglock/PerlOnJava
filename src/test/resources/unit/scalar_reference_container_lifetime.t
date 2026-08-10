use strict;
use warnings;

use Scalar::Util qw();
use Test::More tests => 4;

{
    package Local::ScalarRefCache;

    sub new {
        my ($class) = @_;
        return bless { entries => {}, fifo => [] }, $class;
    }

    sub set {
        my ($self, $key, $value) = @_;
        if (my $old_value_ref = $self->{entries}{$key}) {
            $$old_value_ref = undef;
        }
        my $value_ref = \$value;
        Scalar::Util::weaken($self->{entries}{$key} = $value_ref);
        $self->_update_fifo($key, $value_ref);
        $value;
    }

    sub remove {
        my ($self, $key) = @_;
        my $value_ref = delete $self->{entries}{$key};
        my $value = $$value_ref;
        $$value_ref = undef;
        $value;
    }

    sub _update_fifo {
        my ($self, $key, $value_ref) = @_;
        push @{$self->{fifo}}, [ $key, $value_ref ];
    }
}

my $cache = Local::ScalarRefCache->new;

$cache->set(a => Local::TrackedValue->new);
is($Local::TrackedValue::count, 1, 'scalar referent survives through a nested container');
$cache->set(a => 2);
is($Local::TrackedValue::count, 0, 'replacing the referent releases its value');

$cache->set(b => Local::TrackedValue->new);
is($Local::TrackedValue::count, 1, 'a second referent is retained');
$cache->remove('b');
is($Local::TrackedValue::count, 0, 'removing the referent releases its value');

package Local::TrackedValue;

our $count = 0;

sub new {
    my $class = shift;
    ++$count;
    bless {}, $class;
}

sub DESTROY {
    --$count;
}
