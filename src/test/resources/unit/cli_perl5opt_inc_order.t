use strict;
use warnings;
use File::Spec;

local $ENV{PERL5OPT} = '-Mlib=optm1 -Iopti1 -Mlib=optm2 -Iopti2';
my $path_separator = $^O eq 'MSWin32' ? ';' : ':';
local $ENV{PERL5LIB} = join($path_separator, 'e1', 'e2');
my $output_path = File::Spec->catfile(
    File::Spec->tmpdir, "perlonjava-cli-inc-$$.txt",
);
my @command = (
    ($ENV{PERLONJAVA_EXECUTABLE} || $^X),
    '-Ii1', '-Mlib=m1', '-Ii2', '-Mlib=m2',
    '-e', 'open my $out, q(>), $ARGV[0] or die $!; print {$out} join(q( ), @INC); close $out or die $!',
    $output_path,
);
my $status = system @command;
open my $output, '<', $output_path or die "could not read child output: $!";
my $inc = <$output> // '';
close $output;
unlink $output_path;
my $ok = $status == 0
    && $inc =~ /^\Qoptm2 optm1 m2 m1 opti2 opti1 i1 i2 e1 e2\E\b/;
print "1..1\n";
print(($ok ? 'ok' : 'not ok'),
    " 1 - PERL5OPT -I and -M options precede command-line options in @INC\n");
