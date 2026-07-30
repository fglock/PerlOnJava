package GlobSlotRegistry;

use strict;
use warnings;
use Symbol qw(gensym);

sub new {
    my ($class) = @_;
    my $self = gensym();
    bless $self, $class;
    $ {*$self}{Keys} = {};
    return $self;
}

sub register_handlers {
    my ($self, $names, @handlers, $handlers) = @_;
    if (ref $names eq 'HASH') {
        $handlers = [values %$names];
        $names = [keys %$names];
    }
    else {
        $handlers = \@handlers;
        $handlers = $_[2] if @_ == 3 && ref($_[2]) eq 'ARRAY';
    }
    @{$ {*$self}{Keys}}{@$names} = @$handlers;
}

sub registered_keys {
    my ($self) = @_;
    return sort keys %{$ {*$self}{Keys}};
}

1;
