use strict;
use warnings;
use Test::More;
use IPC::Open3 qw(open3);
use Symbol qw(gensym);

for my $case (
    [ 'qr!@{s{0})(?{!;', '"})"', 1 ],
    [ "my (\$x, %y, \@z);\nqq!\$x\\U \$z[0] \$y{a}\\E \$z[1]!;\nqq!\$x\\U\@{s{0})(?{!;",
      '")("', 3 ],
) {
    my ($source, $near, $line) = @$case;
    my $launcher = $^X eq 'jperl' ? './jperl' : $^X;
    my $stderr = gensym;
    my $pid = open3(undef, my $stdout, $stderr, $launcher, '-e', $source);
    my $output = do { local $/; <$stdout> } . do { local $/; <$stderr> };
    waitpid $pid, 0;

    ok($? != 0, 'malformed nested interpolation fails to compile');
    is($output,
        "syntax error at -e line $line, near $near\n"
            . "Execution of -e aborted due to compilation errors.\n",
        'nested interpolation retains Perl diagnostic context');
}

done_testing;
