use strict;
use warnings;
use File::Spec;
use Test::More;

my $tmpdir = File::Spec->tmpdir;
my $script = File::Spec->catfile($tmpdir, "perlonjava-shebang-comment-$$.pl");
my $output = File::Spec->catfile($tmpdir, "perlonjava-shebang-comment-$$.out");
open my $fh, '>', $script or die "open $script: $!";
print {$fh} "#!perl -w ## no critic (RequireVersionVar)\n";
print {$fh} 'open my $out, ">", "' . $output . '" or die $!;' . "\n";
print {$fh} 'print {$out} join("|", @ARGV), "\n";' . "\n";
close $fh or die "close $script: $!";

my $status = system $^X, $script, '-DHTTP_ACCEPT_ENCODING=gzip', 'simple', 'Hello World!';
is($status, 0, 'script with trailing shebang comment executes');

open my $out, '<', $output or die "open $output: $!";
my $actual = do { local $/; <$out> };
close $out or die "close $output: $!";
is($actual, "-DHTTP_ACCEPT_ENCODING=gzip|simple|Hello World!\n",
    'trailing shebang comment does not become script arguments or output');

unlink $script, $output;
done_testing();
