use v5.40;
use Test::More tests => 8;

SKIP: {
    skip('Devel::Peek was not built', 2);
}

my ($x, $ok, $zero_ok, $error, $ran_after, $nested_error);
{
    local ${^MAX_NESTED_EVAL_BEGIN_BLOCKS} = 0;
    $x = 0;
    $ok = eval 'BEGIN { $x++ } 1';
    $zero_ok = $ok;
    $error = $@;
    $ran_after = $x;

    ${^MAX_NESTED_EVAL_BEGIN_BLOCKS} = 2;
    $ok = eval 'sub f { my $n= shift; eval q[BEGIN { $x++; f($n-1) if $n>0 } 1] or die $@ } f(3); 1';
    $nested_error = $@;
}

ok(!$zero_ok, 'a zero nested-BEGIN limit rejects BEGIN in eval STRING');
like($error, qr/Too many nested BEGIN blocks, maximum of 0 allowed/,
     'eval reports the nested-BEGIN limit');
is($ran_after, 0, 'the blocked BEGIN body does not run');

ok(!$ok,
   'a nested eval STRING BEGIN beyond the configured limit is rejected');
like($nested_error, qr/Too many nested BEGIN blocks, maximum of 2 allowed/,
     'nested eval reports the configured BEGIN limit');
is($x, 2, 'BEGIN blocks run up to the configured nesting limit');
