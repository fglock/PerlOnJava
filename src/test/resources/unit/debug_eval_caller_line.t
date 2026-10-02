use strict;
use warnings;
use File::Spec;
my $output_path = File::Spec->catfile(
    File::Spec->tmpdir, "perlonjava-debug-eval-line-$$.txt",
);
my $unit_lib = File::Spec->rel2abs(
    File::Spec->catdir(qw(src test resources unit lib)),
);
my $program = <<'PERL';
BEGIN { $^P = 0x122 }
our @lines;
sub DB::DB {
    my (undef, $file, $line) = caller;
    return unless $file =~ /^\(eval \d+\)\[.*-e:\d+\]/;
    push @lines, $line;
}
eval "++\$b;\n{\n++\$b;\n}\n++\$b;\n";
open my $out, '>', $ARGV[0] or die $!;
print {$out} join(' ', @lines), "\n";
@lines = ();
eval "++\$b;\nfor (my \$a = 1; \$a <= 2; ++\$a) {\n++\$b;\n}\n++\$b;\n";
print {$out} join(' ', @lines), "\n";
close $out;
PERL
my @command = (
    ($ENV{PERLONJAVA_EXECUTABLE} || $^X),
    "-I$unit_lib", '-d:switchd_empty', '-e', $program, $output_path,
);
my $status = system @command;
open my $output, '<', $output_path or die "could not read debugger output: $!";
my $text = do { local $/; <$output> // '' };
close $output;
unlink $output_path;
my $ok = $status == 0 && $text eq "1 3 3 5\n1 2 3 3 5\n";
print "1..1\n";
print(($ok ? 'ok' : 'not ok'),
    " 1 - eval debugger caller reports block and loop COP lines\n");
