use Test::More;

my $same_version_ok = eval q{
    package UseVersion::First;
    use 5.010001;
    package UseVersion::Second;
    use 5.010001;
    1;
};
ok($same_version_ok, 'repeating the same use VERSION across package declarations is allowed');

my $ok = eval "use v5.20;\nuse v5.39;\n1";
ok(!$ok, 'second use VERSION is rejected');
like($@, qr/use VERSION of 5\.39 or above is not permitted/, 'reports the high-version conflict');

done_testing;
