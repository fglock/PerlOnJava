use strict;
use warnings;
use Test::More;

my $loaded = eval q{
    use Function::Parameters;

    fun add($left, $right = 1) { $left + $right }
    fun item_count(@items) { scalar @items }
    package FunctionParametersMethod;
    method sum($self: $value = 2) { $self->{base} + $value }
    package main;
    1;
};
if (!$loaded && $@ =~ /Can't locate Function\/Parameters\.pm/) {
    plan skip_all => 'Function::Parameters is not installed in system Perl';
}
die $@ unless $loaded;

is(add(4), 5, 'fun declaration uses an optional signature default');
is(add(4, 3), 7, 'fun declaration accepts all positional parameters');
is(item_count(qw(one two three)), 3, 'fun declaration supports a slurpy array');
my $object = bless { base => 40 }, 'FunctionParametersMethod';
is($object->sum, 42, 'method declaration binds its explicit invocant and default');
is($object->sum(3), 43, 'method declaration accepts an explicit positional value');

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
