use strict;
use warnings;
use Config;
BEGIN {
    if (!$Config{useithreads}) {
        print "1..0 # SKIP Perl was built without ithreads\n";
        exit;
    }
}
use threads;
use File::Temp qw(tempfile);

print "1..4\n";
my ($fh, $path) = tempfile();
binmode($fh, ':raw');
print {$fh} "abcdef";
seek($fh, 0, 0);

my $thread = threads->create(sub {
    my $buffer = '';
    read($fh, $buffer, 2);
    close($fh);
    return $buffer;
});
print $thread->join eq 'ab' ? "ok 1 - child reads inherited filehandle\n"
                            : "not ok 1 - child reads inherited filehandle\n";
my $buffer = '';
read($fh, $buffer, 2);
print $buffer eq 'cd' ? "ok 2 - inherited handles share file position\n"
                      : "not ok 2 - inherited handles share file position [$buffer]\n";
print defined(fileno($fh)) ? "ok 3 - parent remains open after child close\n"
                           : "not ok 3 - parent remains open after child close\n";

my $scalar = 'wxyz';
open(my $memory, '<', \$scalar) or die $!;
my $memory_thread = threads->create(sub {
    my $value = '';
    read($memory, $value, 1);
    return $value;
});
$memory_thread->join;
my $value = '';
read($memory, $value, 1);
print $value eq 'w' ? "ok 4 - scalar handle position is cloned\n"
                    : "not ok 4 - scalar handle position is cloned [$value]\n";

close($fh);
unlink($path);
