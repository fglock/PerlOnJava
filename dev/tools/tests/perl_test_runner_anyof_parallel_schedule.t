use strict;
use warnings;

use File::Path qw(make_path);
use File::Basename qw(basename);
use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use Test::More;

my $root = File::Spec->rel2abs(File::Spec->catdir($FindBin::Bin, '..', '..', '..'));
my $runner = File::Spec->catfile($root, 'dev', 'tools', 'perl_test_runner.pl');
my $temporary = tempdir(CLEANUP => 1);
my $fake_jperl = File::Spec->catfile($temporary, 'fake-jperl');
my $anyof_dir = File::Spec->catdir($temporary, 'perl5_t', 't', 're');
make_path($anyof_dir);

write_file($fake_jperl, "#!/usr/bin/env perl\nexec \$^X, \@ARGV;\n");
chmod 0755, $fake_jperl or die "chmod $fake_jperl failed: $!";

my $log = File::Spec->catfile($temporary, 'schedule.log');
my $anyof = File::Spec->catfile($anyof_dir, 'anyof.t');
my @ordinary = map {
    File::Spec->catfile($temporary, "ordinary-$_.t")
} 1 .. 3;
for my $test_file ($anyof, @ordinary) {
    write_file($test_file, <<'TEST');
use strict;
use warnings;
use File::Basename qw(basename);
use Time::HiRes qw(time sleep);
my $log = $ENV{RUNNER_ANYOF_SCHEDULE_LOG} or die "missing schedule log\n";
my $kind = $0 =~ /anyof\.t\z/ ? 'anyof' : basename($0);
open my $fh, '>>', $log or die "cannot append $log: $!";
print {$fh} "start $kind ", time(), "\n";
close $fh;
sleep($kind eq 'anyof' ? 1.5 : 0.2);
open $fh, '>>', $log or die "cannot append $log: $!";
print {$fh} "end $kind ", time(), "\n";
close $fh;
print "1..1\nok 1 - completed\n";
TEST
}

local $ENV{RUNNER_ANYOF_SCHEDULE_LOG} = $log;
open my $command, '-|', $^X, $runner, '--jperl', $fake_jperl,
    '--jobs', '5', '--timeout', '5', $anyof, @ordinary
    or die "cannot start test runner: $!";
my $output = do { local $/; <$command> };
ok(close $command, 'five-unit anyof schedule completes') or diag($output // '');

open my $log_fh, '<', $log or die "cannot read $log: $!";
my %event;
while (<$log_fh>) {
    my ($kind, $file, $time) = split;
    $event{$file}{$kind} = $time;
}
close $log_fh;

ok(defined $event{anyof}{start}, 'anyof fixture starts');
for my $ordinary (@ordinary) {
    my $name = basename($ordinary);
    ok(defined $event{$name}{start}, "$name starts");
    ok($event{$name}{start} < $event{anyof}{end},
        "$name overlaps anyof at --jobs 5");
}

done_testing;

sub write_file {
    my ($path, $contents) = @_;
    open my $fh, '>', $path or die "cannot write $path: $!";
    print {$fh} $contents;
    close $fh or die "cannot close $path: $!";
}
