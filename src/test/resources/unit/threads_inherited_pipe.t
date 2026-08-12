use strict;
use warnings;

use threads;

print "1..3\n";
my $test_number = 0;
sub ok_ {
    my ($condition, $name) = @_;
    ++$test_number;
    print($condition ? "ok " : "not ok ", "$test_number - $name\n");
}

pipe(my $reader, my $writer) or die "pipe: $!";

my $worker = threads->create(sub {
    close $writer;
    my $line = <$reader>;
    close $reader;
    return defined($line) ? $line : '<eof>';
});

close $reader;
ok_(print($writer "message from parent\n"),
    'parent writes through its pipe endpoint');
ok_(close($writer), 'parent closes its pipe endpoint independently');
ok_($worker->join eq "message from parent\n",
    'child inherits the reader endpoint and receives parent data');
