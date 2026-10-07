use strict;
use warnings;
use Test::More;
use IPC::Open3 qw(open3);
use Symbol qw(gensym);

if (@ARGV && $ARGV[0] eq '--child') {
    eval q{
        END { print "OLDER_END=ran\n"; }
        END {
            print "ACTIVE_END=ran\n";
            die "intentional END callback failure\n";
        }
    };
    die $@ if $@;
    exit 0;
}

local $ENV{PERL5LIB} = join($^O eq 'MSWin32' ? ';' : ':', grep { !ref($_) } @INC);
my $timeout_command = $ENV{PERLONJAVA_TIMEOUT_COMMAND} // 'timeout';
my $stderr = gensym();
my $pid = open3(undef, my $stdout, $stderr,
    $timeout_command, '60', $^X, $0, '--child');
local $/;
my $output = <$stdout> // '';
my $error = <$stderr> // '';
close $stdout;
close $stderr;
waitpid($pid, 0);
my $status = $? >> 8;
$output .= $error;
is($status, 22, 'an uncaught END callback die matches Perl 5.45.4 exit status');
like($output, qr/ACTIVE_END=ran/, 'the failing END callback ran');
like($output, qr/OLDER_END=ran/, 'remaining END blocks run after a callback dies');
like($output, qr/intentional END callback failure/,
    'the original END callback error remains visible');
done_testing();
