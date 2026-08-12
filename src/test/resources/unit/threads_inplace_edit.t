use strict;
use warnings;
use File::Temp qw(tempfile);
use threads;

print "1..3\n";
my $number = 0;
sub check {
    my ($condition, $name) = @_;
    ++$number;
    print($condition ? "ok " : "not ok ", $number, " - ", $name, "\n");
}

my ($source, $path) = tempfile();
print {$source} "before\n";
close $source;

@ARGV = ($path);
local $^I = '';
while (<>) {
    threads->create(sub { return 1 })->join;
    print "after\n";
}

check(1, 'stdout is restored after threaded in-place editing');
open my $result, '<', $path or die "Cannot read $path: $!";
my $contents = do { local $/; <$result> };
close $result;
check($contents eq "after\n", 'in-place output replaces the source contents');
check(unlink($path), 'temporary edited file is removed');
