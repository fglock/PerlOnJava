use strict;
use warnings;
use Test::More;

my @destroyed;

{
    package NestedFactoryBase;
    sub new {
        my $class = shift;
        my $cfg = defined $_[0] && ref($_[0]) eq 'HASH' ? shift : { @_ };
        my $self = bless {}, $class;
        return $self->_init($cfg) ? $self : undef;
    }
    sub DESTROY { 1 }
}

{
    package NestedFactoryContext;
    our @ISA = ('NestedFactoryBase');
    sub _init {
        my ($self) = @_;
        my @initialization_steps = qw(provider plugins filters);
        $self->{stash} = { alive => scalar @initialization_steps };
        return $self;
    }
    sub alive { $_[0]->{stash}->{alive} }
    sub DESTROY {
        push @destroyed, 'context';
        undef $_[0]->{stash};
    }
}

{
    package NestedFactoryConfig;
    sub context { NestedFactoryContext->new($_[1]) }
    sub service { NestedFactoryService->new($_[1]) }
}

{
    package NestedFactoryService;
    our @ISA = ('NestedFactoryBase');
    sub _init {
        my ($self, $cfg) = @_;
        $self->{context} = NestedFactoryConfig->context($cfg);
        return $self;
    }
    sub alive { $_[0]->{context}->alive }
}

{
    package NestedFactoryTemplate;
    our @ISA = ('NestedFactoryBase');
    sub _init {
        my ($self, $cfg) = @_;
        $self->{service} = NestedFactoryConfig->service($cfg);
        return $self;
    }
    sub alive { $_[0]->{service}->alive }
}

my $templates = [
    one   => NestedFactoryTemplate->new(MODE => 1),
    two   => NestedFactoryTemplate->new(MODE => 2),
    three => NestedFactoryTemplate->new(MODE => 3),
    four  => NestedFactoryTemplate->new(MODE => 4),
];

is_deeply(\@destroyed, [],
    'nested contexts survive sibling factory construction');
my @alive;
for (my $i = 1; $i < @$templates; $i += 2) {
    push @alive, $templates->[$i]->alive;
}
is_deeply(\@alive, [3, 3, 3, 3],
    'lexical array initialization does not drain caller constructor mortals');

undef $templates;
is(scalar @destroyed, 4,
    'nested contexts destroy only when the owning aggregate is released');

done_testing;
