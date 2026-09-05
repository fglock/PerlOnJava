use strict;
use warnings;

use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use JSON::PP;
use Test::More;

my $root = File::Spec->rel2abs(
    File::Spec->catdir($FindBin::Bin, '..', '..', '..'));
my $runner = File::Spec->catfile(
    $root, 'dev', 'tools', 'perl_test_runner.pl');
my $temporary = tempdir(CLEANUP => 1);
my $fake_jperl = File::Spec->catfile($temporary, 'fake-jperl');
my $test_file = File::Spec->catfile($temporary, 'format-tap.t');
my $json_file = File::Spec->catfile($temporary, 'result.json');

write_file($fake_jperl, <<'FAKE_JPERL');
#!/usr/bin/env perl
exec $^X, @ARGV;
FAKE_JPERL
chmod 0755, $fake_jperl or die "chmod $fake_jperl failed: $!";

write_file($test_file, <<'FORMAT_TAP');
print "1..8\n";
print "ok 1 - ordinary TAP\n";
print "format textok 2 - assertion attached to format output\n";
print "   <ok 3 - assertion after format padding\n";
print "ok 4 - ordinary TAP after formats\n";
print "ok 5\n";
print "ok 6 a TAP description without a hyphen\n";
print "ok 7ok <format error after a bare assertion\n";
print "not ok 8 # TODO expected bare assertion failure\n";
FORMAT_TAP

open my $command, '-|', $^X, $runner,
    '--jperl', $fake_jperl,
    '--jobs', '1',
    '--timeout', '5',
    '--output', $json_file,
    $test_file
    or die "cannot start test runner: $!";
my $runner_output = do { local $/; <$command> };
ok(close $command, 'format TAP fixture completes') or diag($runner_output // '');

open my $json_fh, '<:raw', $json_file or die "cannot read $json_file: $!";
my $document = JSON::PP->new->utf8->decode(do { local $/; <$json_fh> });
close $json_fh;
my ($result) = values %{$document->{results}};
is($result->{status}, 'pass', 'format-continuation TAP is recognized');
is($result->{ok_count}, 8, 'every top-level format-concatenated and bare assertion is counted');
is($result->{incomplete_tests}, 0, 'format output does not create a false incomplete run');

done_testing;

sub write_file {
    my ($path, $contents) = @_;
    open my $fh, '>', $path or die "cannot write $path: $!";
    print {$fh} $contents;
    close $fh or die "cannot close $path: $!";
}
