use strict;
use warnings;
use Test::More;
use File::Temp qw(tempfile);

my ($script, $path) = tempfile();
print {$script} <<'PROGRAM';
my $i = 0;
for (qw(a b)) {
    print $i++ . " $_\n";
}
my $j = 2;
print($j++ . " c\n");
PROGRAM
close $script or die "close child script: $!";

open my $child, '-|', $^X, $path or die "run child: $!";
my $output = do { local $/; <$child> };
close $child;
unlink $path or die "unlink child script: $!";

is($output, "0 a\n1 b\n2 c\n", 'postfix increment concatenates in print arguments');
done_testing();
