use strict;
use warnings;
use Test::More;
use IPC::Open3 qw(open3);
use Symbol qw(gensym);

for my $case (
    ["no feature 'apostrophe_as_package_separator';\nsub 'Hello'_he_said (_);",
        qr/(?:Bareword found where operator expected \(Missing operator before "_he_said"\?\) at - line 2, near "'Hello'_he_said"\n)?Illegal declaration of anonymous subroutine at - line 2(?:, near "sub 'Hello'")?/,
        'subroutine name'],
    ["no feature 'apostrophe_as_package_separator';\nformat 'one =\nok \@<< - format 'foo still works\n\$test\n.",
        qr/syntax error at - line 3, near "ok \@<< - format '"\n  \(Might be a runaway multi-line '' string starting on line 2\)/,
        'format name'],
) {
    my ($source, $expected, $name) = @$case;
    my $launcher = $^X eq 'jperl' ? './jperl' : $^X;
    my $stderr = gensym;
    my $pid = open3(my $stdin, my $stdout, $stderr, $launcher, '-');
    print {$stdin} $source;
    close $stdin;
    my $output = do { local $/; <$stdout> } . do { local $/; <$stderr> };
    waitpid $pid, 0;
    like($output, $expected, "disabled apostrophe package separator rejects $name");
}

done_testing;
