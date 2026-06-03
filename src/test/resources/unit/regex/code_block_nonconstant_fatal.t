use strict;
use warnings;
use Test::More;

my $literal_ok = eval 'my $z = 0; my $rx = qr/x(?{$z++})/; "x" =~ $rx; $z';
is($literal_ok, 1, 'literal non-constant regex code block executes');
is($@, '', 'literal code block compiles without error');

my $pattern = 'x(?{$z})';
my $runtime_ok = eval { my $rx = qr/$pattern/; 1 };
ok(!$runtime_ok, 'runtime non-constant regex code block is fatal by default');
like($@, qr/Eval-group not allowed at runtime/,
    'runtime code block reports the standard re eval requirement');

{
    use re 'eval';

    my $runtime_eval_ok = eval { my $z = 0; my $rx = qr/$pattern/; "x" =~ $rx; ++$z };
    ok($runtime_eval_ok, 'runtime non-constant regex code block compiles with use re eval');
    is($@, '', 'runtime code block with use re eval does not croak');
}

done_testing();
