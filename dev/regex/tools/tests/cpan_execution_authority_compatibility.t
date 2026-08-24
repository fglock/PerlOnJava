use strict;
use warnings;

use Cwd qw(abs_path);
use File::Basename qw(dirname);
use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use Test::More;

my $root = abs_path(File::Spec->catdir($FindBin::Bin, '..', '..', '..'));
my $fixture = File::Spec->catfile($root, 'dev', 'regex', 'tools', 'tests',
    'prepare_cpan_launch_manifest_security.t');
open my $source_fh, '<:raw', $fixture or die "Cannot read $fixture: $!";
local $/;
my $source = <$source_fh>;
close $source_fh or die "Cannot close $fixture: $!";

my $quoted_root = $root;
$quoted_root =~ s/([\\'])/\\$1/g;
$source =~ s{
    my\s+\$root\s*=\s*abs_path\(File::Spec->catdir\(
        \$FindBin::Bin,\s*'\.\.',\s*'\.\.',\s*'\.\.'\)\);
}{my \$root = '$quoted_root';}x
    or die 'Cannot pin transformed fixture root';

my $injection = <<'INJECTION';
my $cpan_runner = File::Spec->catfile($root, 'dev', 'regex', 'tools',
    'run_cpan_acceptance.pl');
is(JSON::PP::is_bool($bridge->{execution_authorized})
        && !$bridge->{execution_authorized}, 1,
    'missing canonical producers derive non-executable bridge authority');
is(JSON::PP::is_bool($marker->{execution_authorized})
        && !$marker->{execution_authorized}, 1,
    'marker carries the same exact non-executable authority boolean');
is($legacy->{mode}, 'prepare-only',
    'non-executable authority can publish only a prepare-only launch');

my $blocked_evidence = File::Spec->catdir($tmp, 'must-not-create-evidence');
my ($blocked_status, $blocked_text) = run_command($^X, $cpan_runner,
    '--authority-marker', "$output.authority.json",
    '--evidence-dir', $blocked_evidence);
isnt($blocked_status, 0, 'consumer rejects non-executable authority');
like($blocked_text,
    qr/integrity-authoritative but not authorized for CPAN execution/,
    'consumer reports the dual authority boundary');
ok(!-e $blocked_evidence,
    'consumer rejects before creating or opening the evidence directory');

# Simulate an attacker who can rewrite and consistently reseal every public
# bundle member, but cannot synthesize canonical producer authentication or
# the actual-byte proof required by the executable bridge schema.
$legacy->{mode} = 'acceptance';
write_json($output, $legacy);
$bridge->{execution_authorized} = JSON::PP::true;
$bridge->{launch_manifest}{sha256} = hash_file($output);
$bridge->{launch_manifest}{size} = -s $output;
$bridge->{tuple_sha256} = sha256_hex(canonical({
    execution_authorized => $bridge->{execution_authorized},
    identity => $bridge->{identity}, inputs => $bridge->{inputs},
    evidence => $bridge->{evidence},
}));
write_json("$output.bridge.json", $bridge);
my (undef, undef, $bridge_name) = File::Spec->splitpath("$output.bridge.json");
write_file("$output.bridge.sha256", hash_file("$output.bridge.json")
    . "  $bridge_name\n");
$marker->{execution_authorized} = JSON::PP::true;
$marker->{tuple_sha256} = $bridge->{tuple_sha256};
$marker->{launch_manifest}{sha256} = hash_file($output);
$marker->{launch_manifest}{size} = -s $output;
$marker->{bridge}{sha256} = hash_file("$output.bridge.json");
$marker->{bridge}{size} = -s "$output.bridge.json";
$marker->{seal}{sha256} = hash_file("$output.bridge.sha256");
$marker->{seal}{size} = -s "$output.bridge.sha256";
write_json("$output.authority.json", $marker);

my $promoted_evidence = File::Spec->catdir($tmp, 'must-not-promote-evidence');
my ($promoted_status, $promoted_text) = run_command($^X, $cpan_runner,
    '--authority-marker', "$output.authority.json",
    '--evidence-dir', $promoted_evidence);
isnt($promoted_status, 0,
    'consistent relabel and reseal cannot promote compatibility authority');
like($promoted_text,
    qr/bridge identity has missing or extra fields|package_producer_sha256/,
    'promotion reaches and fails the canonical executable bridge schema')
    or diag $promoted_text;
ok(!-e $promoted_evidence,
    'failed promotion creates no evidence directory and executes no child');
INJECTION

$source =~ s{(?=subtest 'PATH substitution is inert with explicit Git authority')}
    {$injection\n} or die 'Cannot inject execution-authority reducers';

my $temporary = tempdir(CLEANUP => 1);
my $copy = File::Spec->catfile($temporary,
    'cpan_execution_authority_fixture.t');
open my $copy_fh, '>:raw', $copy or die "Cannot create $copy: $!";
print {$copy_fh} $source or die "Cannot write $copy: $!";
close $copy_fh or die "Cannot close $copy: $!";

open my $child, '-|', $^X, $copy or die "Cannot execute transformed fixture: $!";
my $output = <$child>;
close $child;
my $status = $? >> 8;
is($status, 0, 'byte-identical security fixture plus execution reducers passes')
    or diag($output // '');
like($output // '', qr/missing canonical producers derive non-executable bridge authority/,
    'producer-derived compatibility reducer executed');
like($output // '', qr/consistent relabel and reseal cannot promote compatibility authority/,
    'relabel and reseal non-promotion reducer executed');

done_testing();
