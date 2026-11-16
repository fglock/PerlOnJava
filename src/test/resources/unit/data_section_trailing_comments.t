use strict;
use warnings;
use Test::More;
use File::Temp qw(tempfile);

sub run_source {
    my ($source) = @_;
    my ($script, $path) = tempfile();
    print {$script} $source;
    close $script or die "close child script: $!";
    open my $child, '-|', $^X, $path or die "run child: $!";
    my $output = do { local $/; <$child> };
    close $child;
    unlink $path or die "unlink child script: $!";
    return $output;
}

is(run_source(<<'PROGRAM'), "before\npayload\n", '__DATA__ accepts a trailing comment and retains its payload');
print "before\n";
print <DATA>;
__DATA__ # trailing comment
payload
PROGRAM

is(run_source(<<'PROGRAM'), "before\npayload\n", '__END__ accepts a trailing comment and retains its payload');
print "before\n";
print <DATA>;
__END__ # trailing comment
payload
PROGRAM

is(run_source(<<'PROGRAM'), "before\npayload\n", '__END__; remains a terminal marker');
print "before\n";
print <DATA>;
__END__; # trailing comment
payload
PROGRAM

done_testing();
