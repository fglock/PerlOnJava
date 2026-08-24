use strict;
use warnings;

use Cwd qw(abs_path);
use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use Test::More;

my $root = abs_path(File::Spec->catdir($FindBin::Bin, '..', '..', '..', '..'));
my $fixture = File::Spec->catfile($root, 'dev', 'regex', 'tools', 'tests',
    'cpan_authority_end_to_end.t');
open my $source_fh, '<:raw', $fixture or die "Cannot read $fixture: $!";
local $/;
my $source = <$source_fh>;
close $source_fh or die "Cannot close $fixture: $!";

my $quoted_root = $root;
$quoted_root =~ s/([\\'])/\\$1/g;
$source =~ s{
    my\s+\$root\s*=\s*abs_path\(File::Spec->catdir\(
        \$FindBin::Bin,\s*'\.\.',\s*'\.\.',\s*'\.\.',\s*'\.\.'\)\);
}{my \$root = '$quoted_root';}x
    or die 'Cannot pin transformed end-to-end fixture root';

my $injection = <<'INJECTION';
is(JSON::PP::is_bool($aggregate->{authority}{execution_authorized})
        && $aggregate->{authority}{execution_authorized}, 1,
    'fresh aggregate retains exact executable authority boolean');
is(JSON::PP::is_bool($aggregate->{identity}{execution_authorized})
        && $aggregate->{identity}{execution_authorized}, 1,
    'fresh aggregate identity retains live bridge execution authority');

my ($resume_status, $resume_text) = run_command($^X, $consumer,
    '--authority-marker', "$output.authority.json",
    '--evidence-dir', $acceptance_dir, '--resume');
is($resume_status, 0,
    'fresh executable authority safely resumes its sealed aggregate')
    or diag $resume_text;
like($resume_text, qr/Safe resume verified retained evidence/,
    'fresh resume reaches exact retained-evidence verification');

my $aggregate_path = File::Spec->catfile($acceptance_dir,
    'cpan-acceptance.json');
my $aggregate_bytes = read_file($aggregate_path);
for my $case (
    [missing => sub { delete $_[0]{authority}{execution_authorized} }],
    [null => sub { $_[0]{authority}{execution_authorized} = undef }],
    [string => sub { $_[0]{authority}{execution_authorized} = 'true' }],
    [false => sub { $_[0]{authority}{execution_authorized} = JSON::PP::false }],
    [identity_false => sub {
        $_[0]{authority}{execution_authorized} = JSON::PP::false;
        $_[0]{identity}{execution_authorized} = JSON::PP::false;
    }],
) {
    my $mutated = JSON::PP->new->decode($aggregate_bytes);
    $case->[1]->($mutated);
    write_json($aggregate_path, $mutated);
    write_file("$aggregate_path.sha256", hash_file($aggregate_path)
        . "  cpan-acceptance.json\n");
    my ($status, $text) = run_command($^X, $consumer,
        '--authority-marker', "$output.authority.json",
        '--evidence-dir', $acceptance_dir, '--resume');
    isnt($status, 0, "$case->[0] retained execution authority is rejected");
    like($text, qr/Retained authority envelope is missing or malformed|Retained authority identity drift: execution_authorized/,
        "$case->[0] rejection preserves live marker/bridge/tuple authority");
}
write_file($aggregate_path, $aggregate_bytes);
write_file("$aggregate_path.sha256", hash_file($aggregate_path)
    . "  cpan-acceptance.json\n");
INJECTION

$source =~ s{
    (my\s+\$aggregate\s*=\s*read_json\(File::Spec->catfile\(
        \$acceptance_dir,\s*'cpan-acceptance\.json'\)\);)
}{$1\n$injection}x
    or die 'Cannot inject execution-authority resume reducers';

my $temporary = tempdir(CLEANUP => 1);
my $copy = File::Spec->catfile($temporary,
    'cpan_execution_authority_resume_fixture.t');
open my $copy_fh, '>:raw', $copy or die "Cannot create $copy: $!";
print {$copy_fh} $source or die "Cannot write $copy: $!";
close $copy_fh or die "Cannot close $copy: $!";

open my $child, '-|', $^X, $copy or die "Cannot execute transformed fixture: $!";
my $output = <$child>;
close $child;
my $status = $? >> 8;
is($status, 0, 'true-authority end-to-end fixture plus resume reducers passes')
    or diag($output // '');
like($output // '', qr/fresh executable authority safely resumes its sealed aggregate/,
    'fresh executable resume reducer executed');
like($output // '', qr/identity_false retained execution authority is rejected/,
    'retained false/non-promotable reducer executed');

done_testing();
