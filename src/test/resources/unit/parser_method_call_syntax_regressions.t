use strict;
use warnings;
use Test::More;

{
    package Local::ParserMethodSyntax;

    sub new { bless { calls => [] }, shift }

    sub combine {
        my ($self, @args) = @_;
        push @{ $self->{calls} }, \@args;
        return join ':', @args;
    }

    sub result { bless { value => $_[0] }, 'Local::ParserMethodSyntax::Result' }
}

{
    package Local::ParserMethodSyntax::Result;
    sub value { $_[0]->{value} }
}

{
    package Local::ParserBlockDsl;

    our @definitions;

    # Resource::Silo's exported DSL accepts a list of key/value options,
    # including anonymous subroutines as block arguments.
    sub resource (@) { @definitions = @_ }
}

my $receiver = Local::ParserMethodSyntax->new;
my $indirect_result = combine $receiver 'first', 'second';
is($indirect_result, 'first:second',
    'indirect-object dispatch passes whitespace-separated method arguments');
is_deeply($receiver->{calls}[0], ['first', 'second'],
    'indirect-object method receives all its arguments');

is(Local::ParserMethodSyntax::result('from function')->value,
    'from function',
    'postfix method call applies to a function result');

{
    package Local::ParserBlockDsl;
    resource answer => sub { 42 };
}
is($Local::ParserBlockDsl::definitions[0], 'answer',
    'DSL block argument keeps its preceding key');
is(ref($Local::ParserBlockDsl::definitions[1]), 'CODE',
    'DSL block argument remains an anonymous subroutine');
is($Local::ParserBlockDsl::definitions[1]->(), 42,
    'DSL block argument executes with its body intact');

done_testing;
