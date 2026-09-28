use strict;
use warnings;
use Test::More;
use File::Temp qw(tempfile);

my ($input, $path) = tempfile();
print {$input} "line\n";
close $input or die "close input: $!";

open my $fh, '<', $path or die "open input: $!";
<$fh>;

*_=*.;
is($_, 1, 'the typeglob alias exposes the current input line number');

close $fh or die "close reader: $!";
unlink $path or die "unlink input: $!";

done_testing();
