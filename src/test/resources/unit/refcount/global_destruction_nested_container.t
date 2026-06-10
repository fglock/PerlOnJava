use strict;
use warnings;
use Test::More tests => 2;
use File::Temp qw(tempfile);

SKIP: {
    skip 'nested launcher is unavailable on Windows', 2
        if $^O eq 'MSWin32';

    my ($log_fh, $log_name) = tempfile();
    close $log_fh;

    my ($script_fh, $script_name) = tempfile(SUFFIX => '.pl');
    print {$script_fh} <<'CHILD';
use strict;
use warnings;

package GlobalDestructNested;

sub DESTROY {
    my $path = $_[0]->{path};
    open my $fh, '>>', $path or die "open log: $!";
    print {$fh} "destroy\n";
    close $fh;
}

package main;

our $root = {
    plain => [
        { value => 1 },
        { value => 2 },
    ],
    nested => {
        object => bless({ path => $ARGV[0] }, 'GlobalDestructNested'),
    },
};

END {
    open my $fh, '>>', $ARGV[0] or die "open log: $!";
    print {$fh} "end\n";
    close $fh;
}
CHILD
    close $script_fh or die "close child script: $!";

    my $runner = $^X eq 'jperl' ? './jperl' : $^X;
    my $status = system('timeout', '60', $runner, $script_name, $log_name);
    is($status, 0, 'child process exits cleanly');

    open my $read_fh, '<', $log_name or die "read log: $!";
    my $content = do { local $/; <$read_fh> };
    close $read_fh;
    unlink $script_name;
    unlink $log_name;

    is($content, "end\ndestroy\n",
        'global destruction walks nested object inside an unblessed global container');
}
