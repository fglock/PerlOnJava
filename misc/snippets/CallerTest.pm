package CallerTest;

BEGIN {
    print "# BEGIN block in CallerTest.pm\n";
    my ($package, $filename, $line) = caller();
    print "# Caller in BEGIN: package=$package file=$filename line=$line\n";
}

sub import {
    print "# import() called in CallerTest.pm\n";
    my ($package, $filename, $line) = caller();
    print "# Caller in import: package=$package file=$filename line=$line\n";
}

sub test_caller {
    print "# test_caller() called in CallerTest.pm\n";
    my ($package, $filename, $line) = caller();
    print "# Caller in test_caller: package=$package file=$filename line=$line\n";
    return ($package, $filename, $line);
}

1;

