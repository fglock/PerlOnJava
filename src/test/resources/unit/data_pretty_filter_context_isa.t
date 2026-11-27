use strict;
use warnings;
use Test::More;

package Local::PrettyFilterContext;

sub new {
    my ($class, $object_class, $container_class) = @_;
    return bless { class => $object_class, pclass => $container_class }, $class;
}

sub object_isa {
    return $_[0]->{class} && $_[0]->{class}->isa($_[1]);
}

sub container_isa {
    return $_[0]->{pclass} && $_[0]->{pclass}->isa($_[1]);
}

package Local::PrettyObjectBase;
package Local::PrettyObject;
our @ISA = ('Local::PrettyObjectBase');

package Local::PrettyContainerBase;
package Local::PrettyContainer;
our @ISA = ('Local::PrettyContainerBase');

package main;

my $context = Local::PrettyFilterContext->new(
    'Local::PrettyObject', 'Local::PrettyContainer',
);
is($context->object_isa('Local::PrettyObjectBase'), 1,
    'object_isa dispatches through a class name stored in the context');
is($context->container_isa('Local::PrettyContainerBase'), 1,
    'container_isa dispatches through a class name stored in the context');

done_testing;
