#!/usr/bin/env perl
use strict;
use warnings;

use Test::More;
use Scalar::Util qw(weaken);

BEGIN {
    eval { require Internals; 1 }
        or plan skip_all => 'Internals not available (core refcount helpers)';
}

{
    package GTAC_Object;
    sub new { bless {}, shift }
}

my $strong = GTAC_Object->new;
my $weak = $strong;
weaken($weak);

sub target { return }

sub trampoline {
    unshift @_, $weak;
    goto &target;
}

my $baseline = Internals::SvREFCNT($strong);
trampoline(123) for 1..3;

is Internals::SvREFCNT($strong), $baseline,
    'goto &sub cleans tail-call argument ownership';
ok defined($weak), 'weak reference remains live while strong ref exists';

{
    package GTAC_LocalizedMethod;
    use Scalar::Util qw(weaken);

    sub new { bless {}, shift }

    sub wrap {
        my ($self, $name) = @_;
        weaken $self;
        return sub {
            my $cv = $self->can($name);
            unshift @_, $self;
            goto &$cv;
        };
    }

    sub target { return }
}

my $object = GTAC_LocalizedMethod->new;
my $method = GTAC_LocalizedMethod::wrap($object, 'target');
my $object_weak = $object;
weaken($object_weak);

$method->('warmup');
my $method_baseline = Internals::SvREFCNT($object);

{
    my @captured_args;
    no warnings 'redefine';
    local *GTAC_LocalizedMethod::target = sub { @captured_args = @_ };

    $method->('localized');
    ok Internals::SvREFCNT($object) > $method_baseline,
        'localized tailcall target captures the receiver while in scope';
}

is Internals::SvREFCNT($object), $method_baseline,
    'goto &$cv cleans wrapper lexical coderef before localized target restore';
ok defined($object_weak), 'localized method receiver remains live while strong ref exists';

done_testing;
