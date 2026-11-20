use strict;
use warnings;
use Test::More tests => 1;

{
    package FunctionParametersAutoType;
    use overload '&' => sub {
        bless { name => '(' . $_[0]{name} . '&' . $_[1]{name} . ')' }, ref $_[0]
    }, fallback => 1;
    sub new { bless { name => $_[1] }, $_[0] }
    sub check { 1 }
    sub get_message { 'type check failed' }
}

BEGIN {
    no strict 'refs';
    for my $name (qw(Ta Tb)) {
        my $type = FunctionParametersAutoType->new($name);
        *{$name} = sub { $type };
    }
}

use Function::Parameters;

fun auto_composite(Ta & Tb $value) { $value }

my ($parameter) = Function::Parameters::info(\&auto_composite)->positional_required;
is($parameter->type->{name}, '(Ta&Tb)',
    'auto reification includes overloaded composite type operators');
