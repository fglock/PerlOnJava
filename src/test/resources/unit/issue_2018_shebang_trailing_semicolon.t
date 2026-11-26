use strict;
use warnings;
use File::Temp qw(tempfile);
use Test::More tests => 4;

my ($script_fh, $script) = tempfile(SUFFIX => '.pl', UNLINK => 1);
my ($output_fh, $output) = tempfile(UNLINK => 1);
my $quoted_output = $output;
$quoted_output =~ s/\\/\\\\/g;
$quoted_output =~ s/'/\\'/g;
print {$script_fh} "#!/usr/bin/perl;\n";
print {$script_fh} 'open my $out, ">", q{' . $quoted_output . '} or die $!;' . "\n";
print {$script_fh} 'print {$out} "ran under $^X\\n";' . "\n";
close $script_fh or die "close script: $!";
close $output_fh or die "close output: $!";

my $status = system $^X, $script;
is($status, 0, 'a script with a trailing semicolon in its shebang runs with the active Perl');

open my $captured, '<', $output or die "open output: $!";
my $actual = do { local $/; <$captured> };
close $captured or die "close output: $!";
like($actual, qr/^ran under /, 'the active Perl ran the script body');

my ($env_script_fh, $env_script) = tempfile(SUFFIX => '.pl', UNLINK => 1);
my ($env_output_fh, $env_output) = tempfile(UNLINK => 1);
my $quoted_env_output = $env_output;
$quoted_env_output =~ s/\\/\\\\/g;
$quoted_env_output =~ s/'/\\'/g;
print {$env_script_fh} "#!/usr/bin/env perl; -w\n";
print {$env_script_fh} 'open my $out, ">", q{' . $quoted_env_output . '} or die $!;' . "\n";
print {$env_script_fh} 'print {$out} "ran under $^X\\n";' . "\n";
close $env_script_fh or die "close env script: $!";
close $env_output_fh or die "close env output: $!";

is(system($^X, $env_script), 0,
    'an env Perl shebang with a trailing semicolon runs with the active Perl');
open my $env_captured, '<', $env_output or die "open env output: $!";
my $env_actual = do { local $/; <$env_captured> };
close $env_captured or die "close env output: $!";
like($env_actual, qr/^ran under /, 'the active Perl ran the env shebang script body');
