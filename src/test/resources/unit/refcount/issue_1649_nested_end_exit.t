use strict;
use warnings;
use Test::More;
use IPC::Open3 qw(open3);
use Symbol qw(gensym);

if (@ARGV && $ARGV[0] eq '--child') {
    eval q{
        our $DESTROYED = 0;
        { package Issue1649::NestedExit; sub DESTROY { ++$main::DESTROYED } }
        my $object = bless {}, 'Issue1649::NestedExit';
        END {
            print 'OLDER_CAPTURE=', defined($object) ? "ok\n" : "lost\n";
            print 'DESTROYED=', $DESTROYED, "\n";
        }
        END {
            print 'ACTIVE_CAPTURE=', defined($object) ? "ok\n" : "lost\n";
            exit 0;
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
is($status, 0, 'nested exit from END completes with status zero');
like($output, qr/ACTIVE_CAPTURE=ok/, 'active END reads its captured lexical');
like($output, qr/OLDER_CAPTURE=ok/, 'nested exit dispatches the remaining END capture');
unlike($output, qr/END failed--call queue aborted|JVM Stack Trace|\n\s+main at /,
    'nested exit is not reported as an END failure');
done_testing();
