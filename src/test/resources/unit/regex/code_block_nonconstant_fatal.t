use strict;
use warnings;
use Test::More;

my $literal_ok = eval 'my $z; my $rx = qr/x(?{$z})/; 1';
ok(!$literal_ok, 'literal non-constant regex code block is fatal by default');
ok(index($@, '(?{...}) code blocks in regex not implemented') >= 0,
    'literal code block reports unimplemented regex callback');

my $pattern = 'x(?{$z})';
my $runtime_ok = eval { my $rx = qr/$pattern/; 1 };
ok(!$runtime_ok, 'runtime non-constant regex code block is fatal by default');
ok(index($@, '(?{...}) code blocks in regex not implemented') >= 0,
    'runtime code block reports unimplemented regex callback');

{
    local $ENV{JPERL_REGEX_CODE_BLOCK_NOOP} = '1';

    my $warn_literal_ok = eval 'my $z; "x" =~ qr/x(?{$z})/';
    ok($warn_literal_ok, 'literal non-constant regex code block is a no-op with explicit env gate');

    my $warn_runtime_ok = eval { my $z; my $rx = qr/$pattern/; "x" =~ $rx };
    ok($warn_runtime_ok, 'runtime non-constant regex code block is a no-op with explicit env gate');
}

done_testing();
