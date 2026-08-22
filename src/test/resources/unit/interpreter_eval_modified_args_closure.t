use strict;
use warnings;
use Test::More tests => 2;

sub install_generated {
    my $into = shift;
    my $type = shift;
    my $code = pop;
    my @names = @_;

    my $method = sub { 'foo' };
    my $wrapped = \$method;
    my $generated = q{
        package InterpreterEvalModifiedArgs;
        sub value { $code->($$wrapped, @_) }
    };
    eval $generated;
    die $@ if $@;
}

install_generated('InterpreterEvalModifiedArgs', 'around', 'value', sub {
    my $orig = shift;
    return $orig->(@_) . ' wrapped';
});

is(InterpreterEvalModifiedArgs->value, 'foo wrapped',
    'string eval preserves captures after destructive caller argument use');
my $method = InterpreterEvalModifiedArgs->can('value');
is($method->('InterpreterEvalModifiedArgs'), 'foo wrapped',
    'installed generated method preserves capture identity');
