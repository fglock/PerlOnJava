use v5.40;
use Test::More tests => 3;

my $ran = 0;
my ($result, $error, $ran_after);
{
    local ${^MAX_NESTED_EVAL_BEGIN_BLOCKS} = 0;
    $result = eval 'BEGIN { $ran++ } 1';
    $error = $@;
    $ran_after = $ran;
}

ok(!defined $result, 'a zero nested-BEGIN limit rejects BEGIN in eval STRING');
like($error, qr/Too many nested BEGIN blocks, maximum of 0 allowed/,
     'eval reports the nested-BEGIN limit');
is($ran_after, 0, 'the blocked BEGIN body does not run');
