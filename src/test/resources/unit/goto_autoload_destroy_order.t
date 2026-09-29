use strict;
use warnings;

print "1..1\n";
our $log = '';

{
    package GotoAutoloadDestroy::X;
    our @ISA = ('GotoAutoloadDestroy::Y');

    sub new {
        my ($class, $value) = @_;
        my $self = bless {}, $class;
        $self->value($value);
        $main::log .= "new$value";
        return $self;
    }

    sub DESTROY {
        my ($self) = @_;
        $main::log .= "DESTROY" . $self->value;
    }
}

{
    package GotoAutoloadDestroy::Y;

    sub attribute {
        my ($self, $name) = splice @_, 0, 2;
        return @_ ? $self->{$name} = shift : $self->{$name};
    }

    sub AUTOLOAD {
        our $AUTOLOAD;
        $AUTOLOAD =~ /::([^:]+)$/;
        splice @_, 1, 0, $1;
        goto &attribute;
    }
}

{
    package main;
    my $x = GotoAutoloadDestroy::X->new(1);
    {
        my $y = GotoAutoloadDestroy::X->new(2);
        $log .= $y->value;
    }
    $log .= $x->value;
}

print $log eq 'new1new22DESTROY21DESTROY1'
    ? "ok 1 - goto AUTOLOAD retains caller argument ownership\n"
    : "not ok 1 - goto AUTOLOAD retains caller argument ownership: $log\n";
