use strict;
use warnings;
use File::Temp qw(tempfile);
use Test::More tests => 2;

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
