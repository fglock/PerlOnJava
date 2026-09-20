use strict;
use warnings;
use Test::More;
use IPC::Open3 qw(open3);
use Symbol qw(gensym);

for my $case (
    [ '0 0x@', '0x', 'hexadecimal' ],
    [ '1 0b@', '0b', 'binary' ],
) {
    my ($source, $literal, $kind) = @$case;
    my $launcher = $^X eq 'jperl' ? './jperl' : $^X;
    my $stderr = gensym;
    my $pid = open3(undef, my $stdout, $stderr, $launcher, '-e', $source);
    my $output = do { local $/; <$stdout> } . do { local $/; <$stderr> };
    waitpid $pid, 0;

    my ($left) = $source =~ /\A(\d+)/;
    my $near = "$left $literal";
    is($output,
        "Number found where operator expected (Missing operator before \"$literal\"?) at -e line 1, near \"$near\"\n"
            . "No digits found for $kind literal at -e line 1, near \"$near\@\"\n"
            . "syntax error at -e line 1, near \"$near\"\n"
            . "Execution of -e aborted due to compilation errors.\n",
        'adjacent number and incomplete base literal retain all diagnostics');
}

done_testing;
