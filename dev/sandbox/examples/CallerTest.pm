package CallerTest;

our $levels;

BEGIN {
    $levels = 2;

    print "# BEGIN block in CallerTest.pm\n";
    for ( 0 .. $levels ) {
        my ( $package, $filename, $line ) = caller($_);
        print
"# Caller in BEGIN:       $_ package=$package file=$filename line=$line\n";
    }
}

sub import {
    print "# import() called in CallerTest.pm\n";
    for ( 0 .. $levels ) {
        my ( $package, $filename, $line ) = caller($_);
        print
"# Caller in import:      $_ package=$package file=$filename line=$line\n";
    }
}

sub test_caller {
    print "# test_caller() called in CallerTest.pm\n";
    for ( 0 .. $levels ) {
        my ( $package, $filename, $line ) = caller($_);
        print
"# Caller in test_caller: $_ package=$package file=$filename line=$line\n";
        # return ( $package, $filename, $line );
    }
}

test_caller();

1;

