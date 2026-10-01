use strict;
use warnings;
my $output_path = "/tmp/perlonjava-debug-eval-caller-$$.txt";
my $program = <<'PERL';
BEGIN { $^P = 0x122 }
sub DB::DB {
    my (undef, $file, $line) = caller;
    open my $out, '>>', $ARGV[0] or die $!;
    print {$out} "$file:$line\n";
}
eval "1;\n";
PERL
my @command = (
    ($ENV{PERLONJAVA_EXECUTABLE} || $^X),
    '-Iperl5_t/t/lib', '-d:switchd_empty', '-e', $program, $output_path,
);
my $status = system @command;
open my $output, '<', $output_path or die "could not read debugger output: $!";
my $text = do { local $/; <$output> // '' };
close $output;
unlink $output_path;
my $ok = $status == 0 && $text =~ /\(eval \d+\)\[.*-e:\d+\]/;
print "1..1\n";
print(($ok ? 'ok' : 'not ok'),
    " 1 - debugger eval caller includes source file and line\n");
