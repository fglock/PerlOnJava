use strict;
use warnings;
use File::Temp qw(tempfile);
use Test::More;

my ($writer, $path) = tempfile();
print {$writer} "[% group %]\n" or die "write template: $!";
close $writer or die "close template: $!";
END { unlink $path if defined $path && -e $path }

open my $input, '<', $path or die "open template: $!";
my $output;
open my $output_fh, '>', \$output or die "open scalar output: $!";

while (defined(my $line = <$input>)) {
    $line =~ s/\[% group %\]/hoge foo bar/;
    print {$output_fh} $line or die "write scalar output: $!";
}

close $input or die "close template input: $!";
close $output_fh or die "close scalar output: $!";
is($output, "hoge foo bar\n",
    'filehandle input from a real file writes to scalar-reference output');

done_testing;
