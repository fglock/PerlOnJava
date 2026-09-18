use strict;
use warnings;
use Test::More;
use IPC::Open3 qw(open3);
use Symbol qw(gensym);

for my $source ('07.8p0;', '0b1.2p0;') {
    my $launcher = $^X eq 'jperl' ? './jperl' : $^X;
    my $stderr = gensym;
    my $pid = open3(undef, my $stdout, $stderr, $launcher, '-e', $source);
    my $output = do { local $/; <$stdout> } . do { local $/; <$stderr> };
    waitpid $pid, 0;

    ok($? != 0, "invalid fractional digit in $source fails to compile");
    like($output,
        qr{\ABareword found where operator expected \(Missing operator before "p0"\?\) at -e line 1, near "[82]p0"\nsyntax error at -e line 1, near "[82]p0"\n},
        "invalid fractional digit in $source falls back to the bareword diagnostic");
}

done_testing;
