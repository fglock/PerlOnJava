#!/usr/bin/env perl

use strict;
use warnings;

use File::Find qw(find);
use File::Spec;
use Getopt::Long qw(GetOptions);
use JSON::PP;

my $root = '.';
my $help;
GetOptions(
    'root=s' => \$root,
    'help' => \$help,
) or usage(2);
usage(0) if $help;
usage(2) if @ARGV;

$root = File::Spec->rel2abs($root);
die "Audit root is not a directory: $root\n" unless -d $root;

my @violations;
require_file('dev/tools/perl_test_runner.pl');
require_file('src/main/java/org/perlonjava/runtime/regex/RuntimeRegex.java');
check_file('dev/tools/perl_test_runner.pl', qr/JPERL_UNIMPLEMENTED/,
    'runner still injects unimplemented warn mode');
require_pattern('dev/tools/run_phase36_regex_acceptance.pl',
    qr/JPERL_UNIMPLEMENTED\s*=>\s*undef/,
    'regex acceptance does not clear inherited unimplemented warn mode');
require_pattern('dev/tools/run_phase36_cpan_acceptance.pl',
    qr/JPERL_UNIMPLEMENTED\s*=>\s*undef/,
    'CPAN acceptance does not clear inherited unimplemented warn mode');
scan_tree('dev/tools', qr/\.pl\z/,
    qr/JPERL_UNIMPLEMENTED\s*(?:\}|\])?\s*(?:=>|=|:)\s*['"]?(?:warn|1)\b/,
    'executable tooling still enables unimplemented warn mode', {
        'dev/tools/audit_regex_warn_mode_retirement.pl' => 1,
        'dev/tools/perl_test_runner.pl' => 1,
    });
scan_tree('src/main/java/org/perlonjava/runtime', qr/\.java\z/,
    qr/JPERL_UNIMPLEMENTED|System\.getenv\([^\n]*unimplemented/i,
    'runtime helper still selects behavior from unimplemented warn mode');
scan_tree('src/main/perl/lib/PerlOnJava/CpanDistroprefs', qr/\.yml\z/,
    qr/JPERL_UNIMPLEMENTED\s*:\s*warn/,
    'bundled distropref still enables warn mode');
scan_tree('src/test/resources/unit/regex', qr/\.t\z/,
    qr/JPERL_UNIMPLEMENTED[^\n]*=\s*['"]warn['"]/,
    'regex unit still assumes unimplemented warn mode');
scan_tree('.agents/skills', qr/(?:SKILL|AGENTS)\.md\z/,
    qr/JPERL_UNIMPLEMENTED\s*(?:=|:)?\s*(?:warn|1)/,
    'active skill guidance still recommends unimplemented warn mode');
for my $relative ('AGENTS.md', 'dev/implementation/regex.md',
        'docs/design/joni-callout-fork.md') {
    check_file($relative, qr/JPERL_UNIMPLEMENTED\s*(?:=|:)?\s*(?:warn|1)/,
        'active guidance still recommends unimplemented warn mode');
}

print JSON::PP->new->canonical->encode({
    schema_version => 1,
    audit => 'regex-warn-mode-retirement',
    root => $root,
    violations => \@violations,
    passed => @violations ? JSON::PP::false : JSON::PP::true,
}), "\n";
exit @violations ? 1 : 0;

sub check_file {
    my ($relative, $pattern, $reason) = @_;
    my $path = File::Spec->catfile($root, split m{/}, $relative);
    return unless -f $path;
    open my $fh, '<:raw', $path or die "Cannot read $path: $!\n";
    my $text = do { local $/; <$fh> };
    close $fh or die "Cannot close $path: $!\n";
    push @violations, { path => $relative, reason => $reason }
        if $text =~ $pattern;
}

sub require_file {
    my ($relative) = @_;
    my $path = File::Spec->catfile($root, split m{/}, $relative);
    push @violations, { path => $relative, reason => 'required structural audit target is missing' }
        unless -f $path;
}

sub require_pattern {
    my ($relative, $pattern, $reason) = @_;
    my $path = File::Spec->catfile($root, split m{/}, $relative);
    return unless -f $path;
    open my $fh, '<:raw', $path or die "Cannot read $path: $!\n";
    my $text = do { local $/; <$fh> };
    close $fh or die "Cannot close $path: $!\n";
    push @violations, { path => $relative, reason => $reason }
        unless $text =~ $pattern;
}

sub scan_tree {
    my ($relative_root, $name_pattern, $content_pattern, $reason, $exclude) = @_;
    my $directory = File::Spec->catdir($root, split m{/}, $relative_root);
    return unless -d $directory;
    find({ no_chdir => 1, wanted => sub {
        return unless -f $_ && $_ =~ $name_pattern;
        my $relative = File::Spec->abs2rel($_, $root);
        return if $exclude && $exclude->{$relative};
        check_file($relative, $content_pattern, $reason);
    } }, $directory);
}

sub usage {
    my ($status) = @_;
    print "Usage: $0 [--root REPOSITORY]\n";
    exit $status;
}
