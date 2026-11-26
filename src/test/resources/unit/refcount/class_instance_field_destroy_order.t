use strict;
use warnings;
use v5.36;
use feature 'class';
no warnings 'experimental::class';
use Test::More;

package ClassInstanceFieldDestroyOrder::Notifier;

sub new {
    my ($class, $target, $name) = @_;
    return bless [$target, $name], $class;
}

sub DESTROY {
    my ($self) = @_;
    ${$self->[0]} .= $self->[1];
}

package main;

my $destroyed = '';
{
    class ClassInstanceFieldDestroyOrder::Fields {
        field $x;
        ADJUST {
            $x = ClassInstanceFieldDestroyOrder::Notifier->new(\$destroyed, 'x');
        }

        field $y;
        field $z;
        ADJUST {
            $z = ClassInstanceFieldDestroyOrder::Notifier->new(\$destroyed, 'z');
            $y = ClassInstanceFieldDestroyOrder::Notifier->new(\$destroyed, 'y');
        }
    }

    my $object = ClassInstanceFieldDestroyOrder::Fields->new;
    undef $object;
}

is($destroyed, 'zyx', 'class fields are destroyed in reverse declaration order');
done_testing;
