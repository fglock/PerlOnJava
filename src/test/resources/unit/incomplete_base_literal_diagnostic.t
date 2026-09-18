use strict;
use warnings;
use Test::More;
use IPC::Open3 qw(open3);
use Symbol qw(gensym);

for my $case (
    [ '0x x 2;', 'hexadecimal', '0x ' ],
    [ '0xx 2;',  'hexadecimal', '0xx' ],
    [ '0x_;',    'hexadecimal', '0x_;' ],
    [ '0b;',     'binary', '0b;' ],
) {
    my ($source, $kind, $near) = @$case;
    my $launcher = $^X eq 'jperl' ? './jperl' : $^X;
    my $stderr = gensym;
    my $pid = open3(undef, my $stdout, $stderr, $launcher, '-e', $source);
    my $output = do { local $/; <$stdout> } . do { local $/; <$stderr> };
    waitpid $pid, 0;

    ok($? != 0, "$source fails to compile");
    like($output, qr{\ANo digits found for \Q$kind\E literal at -e line 1, near "\Q$near\E"\n},
        "$source reports the missing base-literal digits");
}

done_testing;
