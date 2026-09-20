use strict;
use warnings;
use Test::More;
use IPC::Open3 qw(open3);
use Symbol qw(gensym);

my $launcher = $^X eq 'jperl' ? './jperl' : $^X;
my $stderr = gensym;
my $pid = open3(undef, my $stdout, $stderr, $launcher, '-e',
    q{use feature 'signatures'; sub foo ($a += 1) {}});
my $output = do { local $/; <$stdout> } . do { local $/; <$stderr> };
waitpid $pid, 0;

ok($? != 0, 'compound assignment in a signature fails to compile');
like($output,
    qr{\AIllegal operator following parameter in a subroutine signature at -e line 1, near "\(\$a \+= 1"\nsyntax error at -e line 1, near "\(\$a \+= 1"\n},
    'signature diagnostic includes the attempted default expression');

done_testing;
