use Test::More;

my $ok = eval 'my $!';
ok(!$ok, 'global-only punctuation variable cannot be lexical');
like($@, qr/Can't use global \$! in "my"/, 'reports the global-only variable');
like($@, qr/near "my \$!/, 'reports the declaration location');

{
    use open ':std', ':utf8';
    $ok = eval qq|my \$\xb6;|;
}
ok(!$ok, 'global-only Unicode punctuation variable cannot be lexical');
like($@, qr/Can't use global \$\x{b6} in "my"/, 'reports the Unicode punctuation variable');

done_testing;
