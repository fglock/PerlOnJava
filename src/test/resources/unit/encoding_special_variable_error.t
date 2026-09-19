use Test::More;

my $ok = eval q{${^ENCODING} = undef;
{ local ${^ENCODING}; }
${^ENCODING} = 42;};
ok(!$ok, '${^ENCODING} is rejected');
like($@, qr/\$\{\^ENCODING\} is no longer supported/, 'reports Perl-compatible diagnostic');
like($@, qr/line 3\./, 'reports the special variable source line');

done_testing;
