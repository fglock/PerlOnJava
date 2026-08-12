use strict;
use warnings;

use File::Temp qw(tempdir);
use POSIX ();
use threads;

print "1..4\n";
my $test = 0;
sub check {
    my ($condition, $name) = @_;
    ++$test;
    print($condition ? "ok " : "not ok ", $test, " - $name\n");
}

my $directory = tempdir(CLEANUP => 1);
my $path = "$directory/child.txt";
my $thread = threads->create(sub {
    my ($child_path) = @_;
    my @identity = (POSIX::getuid(), POSIX::geteuid(), POSIX::getgid(), POSIX::getegid());

    open my $output, '>', $child_path or die "open $child_path: $!";
    print {$output} join(':', @identity), "\n";
    close $output or die "close $child_path: $!";

    my $anchor = qr{\G(?:[\x81-\x9F\xE0-\xFC][\x00-\xFF]|[\x00-\xFF])*?};
    my $matched = (('A' x 8192) . 'B') =~ /(?:${anchor}B)/;
    return [@identity, defined($matched) ? 1 : 0];
}, $path);

my $result = $thread->join;
check(($thread->error || '') eq '', 'native/resource child completed without an error');
check(scalar(@$result) == 5, 'native identity and regex result crossed the join boundary');
check($result->[4], 'deep regex completed inside the child runtime');

open my $input, '<', $path or die "open $path: $!";
chomp(my $written = <$input>);
close $input;
check($written eq join(':', @$result[0 .. 3]),
    'parent observes child-created file contents');
