use strict;
use warnings;
use Test::More tests => 2;

my $script = "/tmp/perlonjava-x-shebang-$$.pl";
open my $fh, '>', $script or die "open $script: $!";
print {$fh} "#!/bin/sh\n";
print {$fh} "eval \"exec $^X -x \\\"\$0\\\" \\\"\$@\\\"\"\n";
print {$fh} "    if 0;\n";
print {$fh} "#!perl -w\n";
print {$fh} "exit \$ARGV[0];\n";
close $fh or die "close $script: $!";
chmod 0755, $script or die "chmod $script: $!";

my $status = system $^X, '-x', $script, 7;
is $status & 0xff, 0, '-x script exited normally';
is $status >> 8, 7, '-x accepts #!perl with switches';

unlink $script;
