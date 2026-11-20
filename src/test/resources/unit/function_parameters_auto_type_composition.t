use strict;
use warnings;
use Test::More tests => 6;

{
    package FunctionParametersAutoType;
    use overload
        '&'   => 'intersection',
        '|'   => 'union',
        '~'   => 'complement',
        '&{}' => 'apply';
    sub new { bless { _name => $_[1] }, $_[0] }
    sub name { $_[0]{_name} }
    sub complement { ref($_[0])->new('~' . $_[0]->name) }
    sub union { ref($_[0])->new('(' . $_[0]->name . '|' . $_[1]->name . ')') }
    sub intersection { ref($_[0])->new('(' . $_[0]->name . '&' . $_[1]->name . ')') }
    sub apply {
        my ($self) = @_;
        return sub {
            return $self unless @_;
            my @args = @{$_[0]};
            ref($self)->new($self->name . '[' . join(',', map { $_->name } @args) . ']');
        };
    }
    sub check { 1 }
    sub get_message { 'type check failed' }
}

BEGIN {
    no strict 'refs';
    for my $name (qw(Ta Tb Td)) {
        my $type = FunctionParametersAutoType->new($name);
        *{$name} = sub { $type->(@_) };
    }
}

use Function::Parameters;

my $auto_composite = fun (Ta & Tb $value) { $value };
my $auto_parameterized = fun (Ta[Tb] $value) { $value };
my $auto_nested = fun (Ta[Tb] | ~Td $value) { $value };
my $auto_quoted_nested = fun ((' Ta[Tb] | ~Td ') $value) { $value };

my ($parameter) = Function::Parameters::info($auto_composite)->positional_required;
is($parameter->type->name, '(Ta&Tb)',
    'auto reification includes overloaded composite type operators');
($parameter) = Function::Parameters::info($auto_parameterized)->positional_required;
is($parameter->type->name, 'Ta[Tb]',
    'auto reification applies bracket arguments through overloaded code refs');
($parameter) = Function::Parameters::info($auto_nested)->positional_required;
is($parameter->type->name, '(Ta[Tb]|~Td)',
    'parameterized types compose with unary and binary operators');
($parameter) = Function::Parameters::info($auto_quoted_nested)->positional_required;
is($parameter->type->name, '(Ta[Tb]|~Td)',
    'quoted automatic type expressions are parsed and reified');

my $missing_type = eval 'fun (NoSuchType $value) {}';
is($missing_type, undef, 'unknown bare type is rejected');
like($@, qr/\AUndefined type name main::NoSuchType /,
    'unknown bare type uses the Function::Parameters diagnostic');
