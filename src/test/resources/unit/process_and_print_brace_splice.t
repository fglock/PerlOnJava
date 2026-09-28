use strict;
use warnings;
use Test::More;
use File::Temp qw(tempfile);

my ($input, $path) = tempfile();
print {$input} "x\ny\n";
close $input or die "close input: $!";

for my $source ('}$_=$.;{', '}{$_=$.}{', '}{*_=*.}{') {
    # Imported fixtures retain their trailing newline; it must not hide the
    # brace pair that splices the implicit -p loop.
    open my $child, '-|', $^X, '-p', '-e', "$source\n", $path
        or die "run -p program: $!";
    my $out = do { local $/; <$child> };
    close $child or die "close -p program: $?";
    is($out, '2', "-p accepts brace-spliced source $source");
}

unlink $path or die "unlink input: $!";

done_testing();
