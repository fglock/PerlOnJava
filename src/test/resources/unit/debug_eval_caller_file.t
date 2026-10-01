use strict;
use warnings;
use File::Spec;
my $output_path = File::Spec->catfile(
    File::Spec->tmpdir, "perlonjava-debug-eval-caller-$$.txt",
);
my $perl5_t_lib = File::Spec->rel2abs(File::Spec->catdir(qw(perl5_t t lib)));
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
    "-I$perl5_t_lib", '-d:switchd_empty', '-e', $program, $output_path,
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
