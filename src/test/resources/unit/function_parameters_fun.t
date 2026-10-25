use strict;
use warnings;
use Test::More;

our $anonymous_add;
my $loaded = eval q{
    use Function::Parameters;

    fun add($left, $right = 1) { $left + $right }
    fun item_count(@items) { scalar @items }
fun option_count(%options) { scalar keys %options }
fun ignore_first($, $value) { $value }
fun default_operators($undefined //= 7, $false ||= 8) { "$undefined:$false" }
fun factorial($value) { $value < 2 ? 1 : $value * factorial($value - 1) }
fun named_options(:$required, :$optional //= 9, %extra) {
    "$required:$optional:" . ($extra{other} // '')
}
    our $anonymous_add = fun ($left, $right = 3) { $left + $right };
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
my $add_info = Function::Parameters::info(\&add);
is_deeply([map { "$_" } $add_info->positional_required], ['$left'],
    'signature info reports required positional parameter names');
is_deeply([map { "$_" } $add_info->positional_optional], ['$right'],
    'signature info reports optional positional parameter names');
is($add_info->args_min, 1, 'signature info reports the minimum argument count');
is($add_info->args_max, 2, 'signature info reports the maximum argument count');
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
is($object->sum, 42, 'method declaration binds its explicit invocant and default');
is($object->sum(3), 43, 'method declaration accepts an explicit positional value');
is($object->default_self, 41, 'method declaration supplies the default $self invocant');
is($object->default_self(5), 45, 'method declaration keeps arguments after its implicit invocant');
is($object->no_arguments, 40, 'method declaration supports an empty user parameter list');
my $method_info = Function::Parameters::info(\&FunctionParametersMethod::default_self);
is("" . $method_info->invocant, '$self', 'signature info reports the method invocant');
is($method_info->args_min, 1, 'method signature info includes its invocant');

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
