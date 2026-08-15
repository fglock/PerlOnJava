use strict;
use warnings;
use threads;
use threads::shared;

print "1..3\n";

my $test :shared = 1;

sub report_ok {
    my ($name) = @_;
    lock($test);
    print "ok ", $test++, " - $name\n";
}

END {
    report_ok('main END block runs in the parent');
}

report_ok('main body runs');

threads->create(sub {
    eval q{ END { report_ok('child END block runs in its owner') } };
    die $@ if $@;
})->join();
