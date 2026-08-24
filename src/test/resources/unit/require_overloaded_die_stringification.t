use strict;
use warnings;
use File::Spec;
use File::Temp qw(tempdir);
use Test::More tests => 5;

my $dir = tempdir(CLEANUP => 1);
my $module = File::Spec->catfile($dir, 'OverloadedRequireDie.pm');

open my $fh, '>', $module or die "open $module: $!";
print {$fh} <<'MODULE';
package OverloadedRequireDie;
use overload '""' => sub { 'OVERLOADED-REQUIRE-DIE' }, fallback => 1;
BEGIN { die bless {}, __PACKAGE__ }
1;
MODULE
close $fh or die "close $module: $!";

local @INC = ($dir, @INC);
my $loaded = eval { require OverloadedRequireDie; 1 };

ok(!$loaded, 'require fails when a BEGIN block dies');
like($@, qr/OVERLOADED-REQUIRE-DIE/, 'require stringifies an overloaded exception object');
unlike($@, qr/=HASH\(/, 'require does not expose the exception reference identity');
like($@, qr/BEGIN failed--compilation aborted/, 'require keeps the compilation diagnostic');
like($@, qr/Compilation failed in require/, 'require keeps the require failure suffix');
