use strict;
use warnings;
use Test::More;

our $anonymous_add;
our $anonymous_method;
our $positive_constraint;
my $loaded = eval q{
    use Function::Parameters;

    package PositiveConstraint;
    sub check { $_[1] > 0 }
    sub get_message { 'value must be positive' }
    package main;
    sub InstanceOf { $positive_constraint }
    our $positive_constraint = bless {}, 'PositiveConstraint';

    fun add($left, $right = 1) { $left + $right }
    fun constrained(($positive_constraint) $value) { $value }
    fun constrained_instance((InstanceOf['GraphQL::Type']) $value) { $value }
    fun item_count(@items) { scalar @items }
fun option_count(%options) { scalar keys %options }
fun ignore_first($, $value) { $value }
fun default_operators($undefined //= 7, $false ||= 8) { "$undefined:$false" }
fun factorial($value) { $value < 2 ? 1 : $value * factorial($value - 1) }
fun named_options(:$required, :$optional //= 9, %extra) {
    "$required:$optional:" . ($extra{other} // '')
}
    our $anonymous_add = fun ($left, $right = 3) { $left + $right };
    our $anonymous_method = method (@) { $self->{base} };
    package AnonymousMethodOverload;
    use overload '""' => method (@) { $self->{text} }, fallback => 1;
    package FunctionParametersMethod;
    method sum($self: $value = 2) { $self->{base} + $value }
    method default_self($value = 1) { $self->{base} + $value }
    method no_arguments() { $self->{base} }
    package main;
    1;
};
if (!$loaded && $@ =~ /Can't locate Function\/Parameters\.pm/) {
    plan skip_all => 'Function::Parameters is not installed in system Perl';
}
die $@ unless $loaded;

is(add(4), 5, 'fun declaration uses an optional signature default');
is(add(4, 3), 7, 'fun declaration accepts all positional parameters');
is(constrained(3), 3, 'typed parameters accept values that satisfy their constraint');
is(constrained_instance(3), 3, 'typed parameters accept bracket-form type expressions');
my $typed_result = eval { constrained(0); 1 };
ok(!$typed_result, 'typed parameters reject values that fail their constraint');
like($@, qr/value must be positive/, 'typed parameter failure uses the constraint message');
my $add_info = Function::Parameters::info(\&add);
is_deeply([map { "$_" } $add_info->positional_required], ['$left'],
    'signature info reports required positional parameter names');
is_deeply([map { "$_" } $add_info->positional_optional], ['$right'],
    'signature info reports optional positional parameter names');
is($add_info->args_min, 1, 'signature info reports the minimum argument count');
is($add_info->args_max, 2, 'signature info reports the maximum argument count');
is($add_info->keyword, 'fun', 'signature info reports the fun keyword');
is(item_count(qw(one two three)), 3, 'fun declaration supports a slurpy array');
is(option_count(one => 1, two => 2), 2, 'fun declaration supports a slurpy hash');
my $items_info = Function::Parameters::info(\&item_count);
is("" . $items_info->slurpy, '@items', 'signature info reports a slurpy array');
is($items_info->args_max, 'Inf', 'signature info marks slurpy signatures unbounded');
is(ignore_first('unused', 'kept'), 'kept', 'fun declarations can ignore a positional parameter');
is(default_operators(undef, 0), '7:8', 'fun declarations support defined-or and or defaults');
is(factorial(5), 120, 'named fun declarations can recurse');
is(named_options(required => 'r', other => 'x'), 'r:9:x',
    'named parameters support defaults and a trailing slurpy hash');
my $named_info = Function::Parameters::info(\&named_options);
is_deeply([map { "$_" } $named_info->named_required], ['$required'],
    'signature info reports required named parameters');
is_deeply([map { "$_" } $named_info->named_optional], ['$optional'],
    'signature info reports optional named parameters');
is("" . $named_info->slurpy, '%extra', 'signature info reports a slurpy hash');
is($named_info->args_min, 2, 'required named parameters count as key/value arguments');
is($anonymous_add->(4), 7, 'fun supports anonymous declarations in expression position');
my $object = bless { base => 40 }, 'FunctionParametersMethod';
is($anonymous_method->($object), 40, 'anonymous method expressions bind an implicit invocant');
my $overloaded = bless { text => 'anonymous method stringification' }, 'AnonymousMethodOverload';
is("$overloaded", 'anonymous method stringification',
    'anonymous method expressions work as overload implementations');
is($object->sum, 42, 'method declaration binds its explicit invocant and default');
is($object->sum(3), 43, 'method declaration accepts an explicit positional value');
is($object->default_self, 41, 'method declaration supplies the default $self invocant');
is($object->default_self(5), 45, 'method declaration keeps arguments after its implicit invocant');
is($object->no_arguments, 40, 'method declaration supports an empty user parameter list');
my $method_info = Function::Parameters::info(\&FunctionParametersMethod::default_self);
is("" . $method_info->invocant, '$self', 'signature info reports the method invocant');
is($method_info->args_min, 1, 'method signature info includes its invocant');
is($method_info->keyword, 'method', 'method signature info reports its keyword');

my $block_scope_result = eval q{
    {
        use Function::Parameters;
        fun block_scoped($value) { $value + 1 }
    }
    block_scoped(4);
};
is($block_scope_result, 5, 'declaration parsed while provider is active in a block');

my $eval_result = eval q{
    use Function::Parameters;
    eval q{
        fun eval_scoped($value) { $value + 2 }
        eval_scoped(4);
    };
};
is($eval_result, 6, 'nested eval inherits the lexical keyword provider');

my $fun_only_result = eval q{
    use Function::Parameters qw(fun);
    fun only_fun($value) { $value + 3 }
    1;
};
is($fun_only_result, 1, 'importing fun alone registers only the requested keyword');
is(only_fun(4), 7, 'selectively imported fun declarations execute');
my $method_not_imported = eval q{
    use Function::Parameters qw(fun);
    method not_imported() { 1 }
    1;
};
ok(!$method_not_imported, 'unrequested method keyword remains unavailable');

my $selective_no_result = eval q{
    use Function::Parameters qw(fun method);
    fun before_no($value) { $value + 1 }
    no Function::Parameters qw(fun);
    package SelectiveMethod;
    method value() { $self->{value} }
    package main;
    1;
};
is($selective_no_result, 1, 'no with an import list removes only selected keywords');
my $selective_object = bless { value => 9 }, 'SelectiveMethod';
is($selective_object->value, 9, 'unremoved method keyword remains active after selective no');

my $arity_result = eval { add(); 1 };
ok(!$arity_result, 'fun declaration rejects too few arguments');
like($@, qr/Too few arguments/, 'fun declaration checks required argument count');

my $disabled = eval q{
    use Function::Parameters;
    no Function::Parameters;
    fun rejected($value) { $value }
    1;
};
ok(!$disabled, 'no Function::Parameters removes its lexical keyword');

my $block_disabled = eval q{
    {
        use Function::Parameters;
        fun scoped_only($value) { $value }
    }
    fun rejected_after_scope($value) { $value }
    1;
};
ok(!$block_disabled, 'keyword registration ends with its lexical block');

done_testing;
