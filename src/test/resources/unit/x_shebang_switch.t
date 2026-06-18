use strict;
use warnings;
use File::Spec;
use Test::More tests => 4;

my $script = "/tmp/perlonjava-x-shebang-$$.pl";
my $out_file = "/tmp/perlonjava-x-shebang-$$.out";
open my $fh, '>', $script or die "open $script: $!";
print {$fh} "#!/bin/sh\n";
print {$fh} "eval \"exec $^X -x \\\"\$0\\\" \\\"\$@\\\"\"\n";
print {$fh} "    if 0;\n";
print {$fh} "#!perl -w -l\n";
print {$fh} "open my \$out, '>', \$ARGV[1] or die \"open output: \$!\";\n";
print {$fh} "select \$out;\n";
print {$fh} "print 'line';\n";
print {$fh} "exit \$ARGV[0];\n";
close $fh or die "close $script: $!";
chmod 0755, $script or die "chmod $script: $!";

my $status = system $^X, '-x', $script, 7, $out_file;
is $status & 0xff, 0, '-x script exited normally';
is $status >> 8, 7, '-x accepts #!perl with switches';

open my $out_fh, '<', $out_file or die "open $out_file: $!";
my $out = do { local $/; <$out_fh> };
close $out_fh or die "close $out_file: $!";
is $out, "line\n", '-x applies switches from extracted shebang';

unlink $script, $out_file;

open my $saved_stderr, '>&', \*STDERR or die "dup STDERR: $!";
open STDERR, '>', File::Spec->devnull or die "redirect STDERR: $!";
$status = system $^X, '-x', '-e', "die;\n", '-e', "#!perl\n", '-e', "warn;\n";
open STDERR, '>&', $saved_stderr or die "restore STDERR: $!";

ok(($status >> 8) != 0, '-x does not extract code from -e fragments');
