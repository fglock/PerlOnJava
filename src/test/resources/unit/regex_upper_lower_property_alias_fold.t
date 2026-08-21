use strict;
use warnings;
use Test::More;

for my $property (qw(Upper Uppercase XPosixUpper)) {
    ok('A' =~ qr/\p{$property}/, "$property contains uppercase without /i");
    ok('a' !~ qr/\p{$property}/, "$property excludes lowercase without /i");
    ok('A' =~ qr/\p{$property}/i, "$property /i contains uppercase");
    ok('a' =~ qr/\p{$property}/i, "$property /i folds lowercase");
}

for my $property (qw(Lower Lowercase XPosixLower)) {
    ok('a' =~ qr/\p{$property}/, "$property contains lowercase without /i");
    ok('A' !~ qr/\p{$property}/, "$property excludes uppercase without /i");
    ok('a' =~ qr/\p{$property}/i, "$property /i contains lowercase");
    ok('A' =~ qr/\p{$property}/i, "$property /i folds uppercase");
}

ok('1' !~ /\p{Upper}/i, 'casing alias /i does not add non-letters');
ok('1' !~ /\p{Lower}/i, 'lowercase alias /i does not add non-letters');

done_testing();
