use strict;
use warnings;
use Test::More;

{
    package UniversalVersionDiagnostic;
    our $VERSION = 2.718;
}

my $ok = eval { UniversalVersionDiagnostic->VERSION('version'); 1 };
ok(!$ok, 'malformed required version fails');
like($@, qr/^Invalid version format/, 'malformed required version has Perl diagnostic');

{
    package UniversalVersionMalformedPackage;
    our $VERSION = 'version';
}

$ok = eval { UniversalVersionMalformedPackage->VERSION(2.719); 1 };
ok(!$ok, 'malformed package version fails');
like($@, qr/^Invalid version format/, 'malformed package version has Perl diagnostic');

done_testing;
