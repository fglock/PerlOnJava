use strict;
use warnings;
use Test::More;
use File::Temp qw(tempfile);
use Time::HiRes qw(stat);

my ($fh, $path) = tempfile();
print {$fh} "stat test\n";
close $fh or die "close $path: $!";

my @core_stat = CORE::stat($path);
my @qualified_stat = Time::HiRes::stat($path);
is(scalar @qualified_stat, 13, 'qualified stat returns the stat fields');
is($qualified_stat[7], $core_stat[7], 'qualified stat reports the file size');
cmp_ok($qualified_stat[9], '>=', $core_stat[9], 'high-resolution mtime is not before core stat');
cmp_ok($qualified_stat[9], '<', $core_stat[9] + 1, 'high-resolution mtime is within the same second');

my @imported_stat = stat($path);
is($imported_stat[7], $core_stat[7], 'imported stat returns the file size');
ok(Time::HiRes::stat($path), 'qualified stat returns true in scalar context');
is(scalar(Time::HiRes::stat("$path.missing")), '', 'stat on a missing file returns false');

unlink $path or die "unlink $path: $!";
done_testing();
